package ec.asistencia;

import com.sun.net.httpserver.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

final class Servidor {
    private record Sesion(Academico.Usuario usuario, Instant vence) {}
    private record Intentos(int total, Instant vence) {}
    private record Reto(Sesion sesion,String direccion,String uso,Instant vence) {}
    private record Prueba(Sesion sesion,String uso,String descriptor,String frontal,Instant vence) {}
    private final Map<String, Sesion> sesiones = new HashMap<>();
    private final Map<String, Intentos> intentos = new HashMap<>();
    private final Map<String,Reto> retosRostro=new HashMap<>();
    private final Map<String,Prueba> pruebasRostro=new HashMap<>();
    private final Properties configuracion = new Properties();
    private final String claveFicticia = Claves.crear(Claves.token());
    private final Clock reloj;

    Servidor(Path archivo) throws IOException {
        try { CifradoFacial.validarClave(); }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Configura ASISTENCIA_CLAVE_FACIAL antes de iniciar Gestión.", e);
        }
        try (var lector = Files.newBufferedReader(archivo)) { configuracion.load(lector); }
        for (String campo : List.of("bd.url", "bd.usuario")) {
            Academico.exigir(!configuracion.getProperty(campo, "").isBlank(), "Completa " + campo + " en la configuración.");
        }
        Academico.exigir(configuracion.containsKey("bd.clave"), "Completa bd.clave en la configuración; puede quedar vacía.");
        reloj = Clock.system(ZoneId.of(configuracion.getProperty("servidor.zona", "America/Guayaquil")));
    }

    void arrancar() throws IOException, SQLException {
        try (Base bd = new Base(configuracion)) {
            try {
                bd.uno("SELECT apellidos FROM cuenta LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054) {
                    throw new IllegalArgumentException("Importa bd/actualizar-apellidos.sql para habilitar los nombres y apellidos.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT periodo_id FROM parcial_periodo LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1146) throw new IllegalArgumentException("Importa bd/actualizar-parciales-periodo.sql.", e);
                throw e;
            }
            try {
                bd.uno("SELECT horario_oferta_id FROM clase LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054) throw new IllegalArgumentException("Importa bd/actualizar-clases-horario.sql.", e);
                throw e;
            }
            bd.uno("SELECT foto_matricula,paralelo FROM matricula LIMIT 0");
            bd.uno("SELECT paralelo FROM clase LIMIT 0");
            bd.uno("SELECT cupos_a,cupos_b FROM materia LIMIT 0");
            try {
                bd.uno("SELECT nivel FROM materia LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054) {
                    throw new IllegalArgumentException("Importa bd/actualizar-jornadas.sql para activar el semestre de las materias.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT periodo_id FROM materia LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054) {
                    throw new IllegalArgumentException("Importa bd/actualizar-ofertas-periodo.sql para asociar materias a sus períodos.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT id FROM semestre_academico LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1146) {
                    throw new IllegalArgumentException("Importa bd/actualizar-semestres.sql para activar el horario semanal.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT estudiante_id,descriptor,consentimiento_en FROM perfil_facial LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1146 || e.getErrorCode() == 1054) {
                    throw new IllegalArgumentException("Importa bd/actualizar-faceid.sql para preparar las plantillas Face ID.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT id FROM materia_catalogo LIMIT 0");
                bd.uno("SELECT materia_id FROM materia_paralelo LIMIT 0");
                bd.uno("SELECT estudiante_id FROM perfil_estudiante LIMIT 0");
                bd.uno("SELECT id FROM matricula_periodo LIMIT 0");
                bd.uno("SELECT id FROM oferta_horario LIMIT 0");
                bd.uno("SELECT id FROM solicitud_justificacion LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1146 || e.getErrorCode() == 1054) {
                    throw new IllegalArgumentException("Importa bd/actualizar-sistema-academico.sql para habilitar el sistema curricular.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT clase_id FROM asistencia_efectiva LIMIT 0");
                bd.uno("SELECT estudiante_id FROM excepcion_matricula_plazo LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1146) {
                    throw new IllegalArgumentException("Importa bd/actualizar-flujo-matricula.sql.", e);
                }
                throw e;
            }
            try {
                bd.uno("SELECT usuario_institucional FROM cuenta LIMIT 0");
                bd.uno("SELECT terminos_version,terminos_aceptados_en,legalizar_desde FROM matricula_periodo_detalle LIMIT 0");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054 || e.getErrorCode() == 1146) {
                    throw new IllegalArgumentException("Importa bd/actualizar-uta.sql después de las migraciones académicas.", e);
                }
                throw e;
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1054) {
                throw new IllegalArgumentException("Importa bd/actualizar-paralelos.sql para actualizar la base de datos.", e);
            }
            throw e;
        }
        String host = configuracion.getProperty("servidor.host", "127.0.0.1");
        int puerto = Integer.parseInt(configuracion.getProperty("servidor.puerto", Integer.toString(Protocolo.PUERTO_PREDETERMINADO)));
        HttpServer servidor = HttpServer.create(new InetSocketAddress(host, puerto), 32);
        servidor.createContext(Protocolo.RUTA, this::atender);
        servidor.createContext("/api/rostro", this::descriptorWeb);
        servidor.createContext("/api/reportes", this::reportePDF);
        servidor.createContext("/", this::paginaWeb);
        // Un solo ejecutor conserva el orden de las validaciones y cambios académicos.
        prepararClasesDeHorarios();
        servidor.start();
        var legalizador = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(tarea -> {
            var hilo = new Thread(tarea, "legalizacion-matriculas");
            hilo.setDaemon(true);
            return hilo;
        });
        legalizador.scheduleWithFixedDelay(this::legalizarMatriculas, 0, 30, java.util.concurrent.TimeUnit.SECONDS);
        System.out.printf("Servidor listo en %s:%d (%s)%n", host, servidor.getAddress().getPort(), reloj.getZone());
    }

    private void prepararClasesDeHorarios() throws SQLException {
        try (Base bd = new Base(configuracion)) {
            var ofertas = bd.consultar("""
                    SELECT DISTINCT m.id FROM materia m
                    JOIN oferta_horario h ON h.materia_id=m.id
                    JOIN semestre_academico s ON s.id=m.periodo_id
                    ORDER BY m.id
                    """);
            bd.iniciar();
            var academico = new Academico(bd, LocalDateTime.now(reloj));
            for (var oferta : ofertas) {
                try {
                    academico.sincronizarClases(Long.parseLong(oferta[0]));
                    bd.confirmar();
                } catch (SQLException | IllegalArgumentException e) {
                    bd.revertir();
                    System.err.println("Revisa el horario de la oferta " + oferta[0] + ": " + e.getMessage());
                }
            }
        }
    }

    private void legalizarMatriculas() {
        LocalDateTime ahora = LocalDateTime.now(reloj);
        try (Base bd = new Base(configuracion)) {
            var pendientes = bd.consultar("""
                    SELECT mp.estudiante_id,d.materia_id
                    FROM matricula_periodo_detalle d JOIN matricula_periodo mp ON mp.id=d.matricula_id
                    JOIN materia m ON m.id=d.materia_id AND m.periodo_id=mp.periodo_id
                    JOIN matricula e ON e.estudiante_id=mp.estudiante_id AND e.materia_id=d.materia_id
                    JOIN perfil_estudiante p ON p.estudiante_id=mp.estudiante_id
                    WHERE d.estado='PENDIENTE' AND e.estado='PENDIENTE' AND e.paralelo=d.paralelo
                      AND p.estado='ACTIVO' AND d.legalizar_desde<=? AND d.terminos_aceptados_en IS NOT NULL
                    ORDER BY d.legalizar_desde,mp.estudiante_id,d.materia_id LIMIT 200
                    """, ahora);
            bd.iniciar();
            var reservas = new Reservas(bd, ahora);
            for (String[] fila : pendientes) {
                try {
                    reservas.legalizarAlCierre(Long.parseLong(fila[0]), Long.parseLong(fila[1]));
                    bd.confirmar();
                } catch (SQLException | RuntimeException e) {
                    bd.revertir();
                    System.err.println("No se pudo legalizar la matrícula: " + e.getMessage());
                }
            }
        } catch (SQLException | RuntimeException e) {
            System.err.println("No se pudo ejecutar la legalización automática: " + e.getMessage());
        }
    }

    void crearAdministrador(String usuario, String nombre, String clave) throws SQLException {
        try (Base bd = new Base(configuracion)) {
            bd.cambiar("INSERT INTO cuenta(usuario,nombre,clave,rol) VALUES(?,?,?,'ADMIN')",
                    Academico.usuario(usuario, false), Academico.texto(nombre, 120, "el nombre"), Claves.crear(clave));
        }
    }

    boolean hayAdministrador() throws SQLException {
        try (Base bd = new Base(configuracion)) {
            return bd.uno("SELECT id FROM cuenta WHERE rol='ADMIN' LIMIT 1") != null;
        }
    }

    private List<String[]> ingresar(Base bd, String accion, String[] campos, Instant ahora) throws SQLException {
        Academico.exigir(campos.length == 2, "Ingresa tu usuario y clave.");
        boolean institucional = accion.equals("ingresarEstudianteInstitucional") || accion.equals("ingresarDocenteInstitucional");
        boolean estudiante = accion.equals("ingresarEstudiante") || accion.equals("ingresarEstudianteInstitucional");
        if (institucional) Academico.exigir(campos[0].strip().toLowerCase(Locale.ROOT).endsWith("@uta.edu.ec"),
                "Ingresa tu correo institucional completo (@uta.edu.ec).");
        String usuario = Academico.usuarioInstitucional(campos[0]);
        String[] fila = accion.equals("ingresarAdmin") ? null : bd.uno("""
                SELECT id,rol,CONCAT_WS(' ',nombre,apellidos),clave FROM cuenta
                WHERE usuario_institucional=? AND rol=?
                """, usuario, estudiante ? "ESTUDIANTE" : "DOCENTE");
        if (fila == null && !institucional && !campos[0].contains("@")) {
            fila = bd.uno("""
                    SELECT id,rol,CONCAT_WS(' ',nombre,apellidos),clave FROM cuenta
                    WHERE usuario=? AND (usuario_institucional IS NULL OR rol='ADMIN')
                    """, usuario);
        }
        String claveIntentos = fila == null ? accion + ":" + usuario : "cuenta:" + fila[0];
        Intentos previos = intentos.get(claveIntentos);
        Academico.exigir(previos == null || previos.total() < 8, "Demasiados intentos. Vuelve a probar en unos minutos.");
        boolean coincide = Claves.verificar(campos[1], fila == null ? claveFicticia : fila[3]);
        boolean rolPermitido = fila != null && switch (accion) {
            case "ingresarEstudiante", "ingresarEstudianteInstitucional" -> fila[1].equals("ESTUDIANTE");
            case "ingresarDocente", "ingresarDocenteInstitucional" -> fila[1].equals("DOCENTE");
            case "ingresarAdmin" -> fila[1].equals("ADMIN");
            case "ingresarGestion" -> fila[1].equals("ADMIN") || fila[1].equals("DOCENTE");
            default -> false;
        };
        if (rolPermitido && fila[1].equals("ESTUDIANTE")) {
            rolPermitido = bd.uno("SELECT estudiante_id FROM perfil_estudiante WHERE estudiante_id=? AND estado='ACTIVO'",
                    Long.parseLong(fila[0])) != null;
        } else if (rolPermitido && fila[1].equals("DOCENTE")) {
            rolPermitido = bd.uno("SELECT docente_id FROM perfil_docente WHERE docente_id=? AND activo=TRUE",
                    Long.parseLong(fila[0])) != null;
        }
        if (!coincide || !rolPermitido) {
            int total = previos == null ? 1 : previos.total() + 1;
            if (intentos.size() < 10_000 || previos != null) {
                intentos.put(claveIntentos, new Intentos(total, previos == null ? ahora.plusSeconds(300) : previos.vence()));
            }
            throw new IllegalArgumentException("Usuario o clave incorrectos.");
        }
        intentos.remove(claveIntentos);
        Academico.exigir(sesiones.size() < 10_000, "Hay demasiadas sesiones activas. Intenta más tarde.");
        var cuenta = new Academico.Usuario(Long.parseLong(fila[0]), fila[1], fila[2]);
        String token = Claves.token();
        sesiones.put(token, new Sesion(cuenta, ahora.plus(Duration.ofHours(8))));
        return Collections.singletonList(new String[]{token, cuenta.rol(), cuenta.nombre()});
    }

    private List<String[]> desafioRostro(Sesion sesion,String[] campos){
        Academico.exigir(sesion.usuario().estudiante(),"Face ID requiere una sesión estudiantil.");
        Academico.exigir(campos.length==1&&Set.of("REGISTRO","ASISTENCIA").contains(campos[0]),"Indica el propósito de la prueba facial.");
        retosRostro.values().removeIf(r->!r.vence().isAfter(reloj.instant()));
        pruebasRostro.values().removeIf(r->!r.vence().isAfter(reloj.instant()));
        Academico.exigir(retosRostro.size()<10_000&&pruebasRostro.size()<10_000,"Hay demasiadas verificaciones faciales activas.");
        String claveIntentos = "facial:" + sesion.usuario().id();
        Instant ahora = reloj.instant();
        Intentos anterior = intentos.get(claveIntentos);
        int total = anterior == null ? 0 : anterior.total();
        Academico.exigir(total < 8, "Espera antes de iniciar otra verificación facial.");
        intentos.put(claveIntentos, new Intentos(total + 1, anterior == null ? ahora.plusSeconds(300) : anterior.vence()));
        String id=Claves.token();String direccion=java.util.concurrent.ThreadLocalRandom.current().nextBoolean()?"IZQUIERDA":"DERECHA";
        retosRostro.put(id,new Reto(sesion,direccion,campos[0],reloj.instant().plusSeconds(60)));
        return Collections.singletonList(new String[]{id,direccion});
    }

    private List<String[]> validarRostro(Sesion sesion,String[] campos){
        Academico.exigir(campos.length==3,"Envía la captura frontal y la respuesta al reto.");
        Reto reto=retosRostro.get(campos[0]);
        Academico.exigir(reto!=null&&reto.sesion()==sesion&&reto.vence().isAfter(reloj.instant()),"La prueba facial venció. Inicia un reto nuevo.");
        retosRostro.remove(campos[0]);
        byte[] frente=imagenBase64(campos[1]),giro=imagenBase64(campos[2]);
        BufferedImage imgFrente,imgGiro;
        try{imgFrente=leerCaptura(frente);imgGiro=leerCaptura(giro);}
        catch(IOException e){throw new IllegalArgumentException("No se pudieron leer las capturas JPEG de la prueba facial.",e);}
        Academico.exigir(imgFrente!=null&&imgGiro!=null,"No se pudieron leer las capturas de la prueba facial.");
        double poseFrente,poseGiro;
        try{poseFrente=FaceId.yaw(imgFrente);poseGiro=FaceId.yaw(imgGiro);}
        catch(IllegalArgumentException e){throw e;}
        catch(Exception | LinkageError e){throw new IllegalArgumentException("No se pudo verificar el movimiento facial. Revisa OpenCV y los modelos.",e);}
        Academico.exigir(Math.abs(poseFrente)<=0.14,"Mira de frente para iniciar la prueba.");
        // La izquierda del estudiante aparece a la derecha en la imagen sin espejo.
        boolean giroValido=reto.direccion().equals("IZQUIERDA")?poseGiro>=0.18:poseGiro<=-0.18;
        Academico.exigir(giroValido&&Math.abs(poseGiro)<=0.85,"El giro no coincide con el reto. Repite la captura siguiendo la instrucción.");
        byte[] descriptor;
        try {
            descriptor = FaceId.descriptor(imgFrente);
            Academico.exigir(Academico.coincideRostro(descriptor, FaceId.descriptor(imgGiro)),
                    "Usa el mismo rostro en ambas capturas.");
        }
        catch(IllegalArgumentException e){throw e;}
        catch(Exception | LinkageError e){throw new IllegalArgumentException("No se pudo generar el perfil facial.",e);}
        String representacion=Base64.getEncoder().encodeToString(descriptor);
        String proof=Claves.token();pruebasRostro.put(proof,new Prueba(sesion,reto.uso(),representacion,campos[1],reloj.instant().plusSeconds(45)));
        return Collections.singletonList(new String[]{proof});
    }

    private static BufferedImage leerCaptura(byte[] jpeg) throws IOException {
        try (var entrada = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
            var lector = ImageIO.getImageReadersByFormatName("JPEG").next();
            try {
                lector.setInput(entrada, true, true);
                Academico.exigir(lector.getWidth(0) <= 1920 && lector.getHeight(0) <= 1080,
                        "La captura supera la resolución permitida.");
                return lector.read(0);
            } finally {
                lector.dispose();
            }
        }
    }

    private static byte[] imagenBase64(String valor){
        try{byte[] imagen=Base64.getDecoder().decode(valor);Academico.exigir(imagen.length>=4&&imagen.length<=750_000&&
                imagen[0]==(byte)0xff&&imagen[1]==(byte)0xd8,"Cada captura JPEG debe tener hasta 750 KB.");return imagen;}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("La captura de prueba de vida no es un JPEG válido.",e);}
    }

    private Prueba consumirPrueba(Sesion sesion, String id, String uso) {
        Prueba prueba = pruebasRostro.get(id);
        Academico.exigir(prueba != null && prueba.sesion() == sesion && prueba.uso().equals(uso)
                && prueba.vence().isAfter(reloj.instant()), "La prueba facial venció o ya se utilizó.");
        pruebasRostro.remove(id);
        return prueba;
    }

    private List<String[]> ejecutar(String[] peticion) throws SQLException, IOException {
        String accion = peticion[0];
        String token = peticion[1];
        String[] campos = Arrays.copyOfRange(peticion, 2, peticion.length);
        Instant ahora = reloj.instant();
        sesiones.values().removeIf(s -> !s.vence().isAfter(ahora));
        intentos.values().removeIf(i -> !i.vence().isAfter(ahora));
        if (accion.equals("salir")) {
            sesiones.remove(token);
            return List.of();
        }
        try (Base bd = new Base(configuracion)) {
            if (accion.equals("ingresarGestion") || accion.equals("ingresarAdmin") || accion.equals("ingresarDocente") || accion.equals("ingresarEstudiante")
                    || accion.equals("ingresarEstudianteInstitucional") || accion.equals("ingresarDocenteInstitucional")) {
                return ingresar(bd, accion, campos, ahora);
            }
            Sesion sesion = sesiones.get(token);
            if (!accion.equals("registrar")) {
                Academico.exigir(sesion != null, "Ingresa de nuevo. Tu sesión no está activa.");
            }
            if (accion.equals("desafioRostro")) {
                if (campos.length == 1 && campos[0].equals("ASISTENCIA") && sesion.usuario().estudiante()) {
                    Academico.exigir(bd.uno("SELECT estudiante_id FROM perfil_facial WHERE estudiante_id=?",
                            sesion.usuario().id()) != null, "Registra primero tu rostro en la pestaña Face ID.");
                }
                return desafioRostro(sesion, campos);
            }
            if(accion.equals("validarRostro"))return validarRostro(sesion,campos);
            if (accion.equals("guardarPerfilFacial")) {
                Academico.exigir(campos.length == 1, "Registra Face ID con la prueba facial.");
                Prueba prueba = consumirPrueba(sesion, campos[0], "REGISTRO");
                campos = new String[]{prueba.descriptor()};
            }
            if (accion.equals("marcar")) {
                Academico.exigir(campos.length == 2, "Marca asistencia con la prueba facial.");
                Prueba prueba = consumirPrueba(sesion, campos[1], "ASISTENCIA");
                campos = new String[]{campos[0], prueba.descriptor(), prueba.frontal()};
            }
            var academico = new Academico(bd, LocalDateTime.ofInstant(ahora, reloj.getZone()));
            bd.iniciar();
            try {
                var respuesta = academico.ejecutar(sesion == null ? null : sesion.usuario(), accion, campos);
                if (sesion != null && Set.of("guardarCuenta", "guardarMateria", "guardarParcial", "guardarParcialPeriodo", "guardarSemestre",
                        "guardarClase", "iniciarClaseAhora", "guardarTemaClase", "guardarHorarioOferta", "matricular", "configurarCupos", "configurarParalelo", "justificar",
                        "cancelar", "guardarCupo", "liberarCupo", "finalizarMatricula", "marcar", "guardarPerfilFacial",
                        "solicitarJustificacion", "resolverJustificacion", "guardarMalla", "guardarCatalogo",
                        "guardarPrerrequisito", "confirmarSeleccion", "retirarFaceId", "guardarConfiguracionAcademica", "guardarResultadoAcademico", "corregirAsistencia", "excepcionMatricula")
                        .contains(accion)) {
                    String referencia = accion.equals("guardarPerfilFacial") ? "perfil facial" : campos.length == 0 ? null : campos[0];
                    bd.cambiar("INSERT INTO auditoria_evento(actor_id,accion,entidad,referencia) VALUES(?,?,?,?)",
                            sesion.usuario().id(), accion, "academico", referencia);
                }
                bd.confirmar();
                if (accion.equals("retirarFaceId")) {
                    long estudiante = sesion.usuario().id();
                    retosRostro.values().removeIf(r -> r.sesion().usuario().id() == estudiante);
                    pruebasRostro.values().removeIf(r -> r.sesion().usuario().id() == estudiante);
                }
                if (accion.equals("guardarCuenta") && !campos[0].isEmpty() ) {
                    long id = Long.parseLong(campos[0]);
                    sesiones.values().removeIf(s -> s.usuario().id() == id);
                }
                return respuesta;
            } catch (SQLException | IOException | RuntimeException e) {
                bd.revertir();
                throw e;
            }
        }
    }


    /** Archivos de la interfaz web, sin permitir rutas arbitrarias. */
    private void paginaWeb(HttpExchange intercambio) throws IOException {
        try (intercambio) {
            if (!"GET".equals(intercambio.getRequestMethod())) {
                intercambio.sendResponseHeaders(405, -1);
                return;
            }
            String ruta = intercambio.getRequestURI().getPath();
            String archivo = switch (ruta) {
                case "/", "/index.html" -> "index.html";
                case "/app.js" -> "app.js";
                case "/style.css" -> "style.css";
                case "/favicon.svg" -> "favicon.svg";
                default -> null;
            };
            if (archivo == null) {
                intercambio.sendResponseHeaders(404, -1);
                return;
            }
            Path recurso = Path.of("web", archivo);
            if (!Files.isRegularFile(recurso)) {
                intercambio.sendResponseHeaders(404, -1);
                return;
            }
            byte[] contenido = Files.readAllBytes(recurso);
            String tipo = archivo.endsWith(".html") ? "text/html; charset=utf-8"
                    : archivo.endsWith(".css") ? "text/css; charset=utf-8"
                    : archivo.endsWith(".svg") ? "image/svg+xml; charset=utf-8"
                    : "text/javascript; charset=utf-8";
            var cabeceras = intercambio.getResponseHeaders();
            cabeceras.set("Content-Type", tipo);
            cabeceras.set("X-Content-Type-Options", "nosniff");
            cabeceras.set("Referrer-Policy", "no-referrer");
            cabeceras.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            cabeceras.set("Cache-Control", "no-cache");
            intercambio.sendResponseHeaders(200, contenido.length);
            intercambio.getResponseBody().write(contenido);
        }
    }

    /** Convierte una foto de cámara en un descriptor usando el Face ID original. */
    private void descriptorWeb(HttpExchange intercambio) throws IOException {
        try (intercambio) {
            if (!"POST".equals(intercambio.getRequestMethod())) {
                intercambio.sendResponseHeaders(405, -1);
                return;
            }
            String token = intercambio.getRequestHeaders().getFirst("X-Asistencia-Sesion");
            Sesion sesion = token == null ? null : sesiones.get(token);
            if (sesion == null || !sesion.vence().isAfter(reloj.instant()) || !sesion.usuario().estudiante()) {
                responderTexto(intercambio, 401, "Sesión de estudiante no válida.");
                return;
            }
            byte[] datos = intercambio.getRequestBody().readNBytes(1_000_001);
            if (datos.length < 4 || datos.length > 1_000_000 || datos[0] != (byte)0xff || datos[1] != (byte)0xd8) {
                responderTexto(intercambio, 400, "Captura una foto JPEG de hasta 1 MB.");
                return;
            }
            try {
                BufferedImage imagen;
                try (var entrada = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(datos))) {
                    var lectores = ImageIO.getImageReaders(entrada);
                    if (!lectores.hasNext()) {
                        responderTexto(intercambio, 400, "No se pudo leer la foto JPEG.");
                        return;
                    }
                    var lector = lectores.next();
                    try {
                        lector.setInput(entrada);
                        if (lector.getWidth(0) > 1920 || lector.getHeight(0) > 1080) {
                            responderTexto(intercambio, 400, "La imagen debe ser de hasta 1920 × 1080.");
                            return;
                        }
                        imagen = lector.read(0);
                    } finally { lector.dispose(); }
                }
                byte[] descriptor = FaceId.descriptor(imagen);
                responderTexto(intercambio, 200, Base64.getEncoder().encodeToString(descriptor));
            } catch (Exception e) {
                System.err.println("No se pudo procesar Face ID: " + e.getClass().getSimpleName());
                responderTexto(intercambio, 422, "No se detectó un rostro válido o falta configurar OpenCV en el servidor.");
            }
        }
    }

    private static Map<String,String> parametros(String query) {
        Map<String,String> valores=new HashMap<>();
        if(query==null)return valores;
        for(String parte:query.split("&")){
            String[] kv=parte.split("=",2);
            valores.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),kv.length==2?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"");
        }
        return valores;
    }

    private void reportePDF(HttpExchange intercambio) throws IOException {
        try{
            if(!"GET".equals(intercambio.getRequestMethod())){intercambio.sendResponseHeaders(405,-1);return;}
            String token=intercambio.getRequestHeaders().getFirst("X-Asistencia-Sesion");
            Sesion sesion=token==null?null:sesiones.get(token);
            if(sesion==null||!sesion.vence().isAfter(reloj.instant())){responderTexto(intercambio,401,"Sesión no válida.");return;}
            var args=parametros(intercambio.getRequestURI().getRawQuery());
            String tipo=args.getOrDefault("tipo","");
            List<String> lineas=new ArrayList<>();
            String titulo,archivo;
            try(Base bd=new Base(configuracion)){
                if(tipo.equals("matricula")){
                    Academico.exigir(sesion.usuario().estudiante(),"El comprobante individual solo está disponible para el estudiante titular.");
                    long periodo=Long.parseLong(args.getOrDefault("periodo","0"));
                    var cab=bd.uno("""
                            SELECT mp.estado,DATE_FORMAT(mp.creada_en,'%Y-%m-%d %H:%i'),
                                COALESCE(DATE_FORMAT(mp.confirmada_en,'%Y-%m-%d %H:%i'),''),s.nombre
                            FROM matricula_periodo mp JOIN semestre_academico s ON s.id=mp.periodo_id
                            WHERE mp.id=? AND mp.estudiante_id=?
                            """,periodo,sesion.usuario().id());
                    Academico.exigir(cab!=null,"No tienes acceso a ese comprobante.");
                    titulo="Comprobante de matrícula";archivo="comprobante-matricula-"+periodo+".pdf";
                    lineas.add("Estudiante: "+sesion.usuario().nombre());
                    lineas.add("Período: "+cab[3]);lineas.add("Estado: "+cab[0]);
                    lineas.add("Solicitud: "+cab[1]);lineas.add("Confirmada: "+(cab[2].isBlank()?"Pendiente de legalización":cab[2]));
                    lineas.add("");lineas.add("ASIGNATURAS, PARALELOS, DOCENTES Y HORARIOS");
                    var filas=bd.consultar("""
                            SELECT m.codigo,m.nombre,d.paralelo,CONCAT_WS(' ',doc.nombre,doc.apellidos),
                                GROUP_CONCAT(CONCAT(ELT(oh.dia_semana,'Lun','Mar','Mié','Jue','Vie'),' ',
                                    TIME_FORMAT(oh.hora_inicio,'%H:%i'),'-',TIME_FORMAT(oh.hora_fin,'%H:%i'),
                                    IF(oh.aula IS NULL,'',CONCAT(' aula ',oh.aula))) ORDER BY oh.dia_semana,oh.hora_inicio SEPARATOR '; '),d.estado
                            FROM matricula_periodo_detalle d JOIN materia m ON m.id=d.materia_id
                            JOIN cuenta doc ON doc.id=m.docente_id LEFT JOIN oferta_horario oh
                                ON oh.materia_id=m.id AND oh.paralelo=d.paralelo AND oh.activo=TRUE
                            WHERE d.matricula_id=? GROUP BY m.id,d.id ORDER BY m.nivel,m.codigo
                            """,periodo);
                    for(String[] f:filas){lineas.add(f[0]+" · "+f[1]+" | Paralelo "+f[2]+" | "+f[3]);lineas.add("Horario: "+(f[4].isBlank()?"No configurado":f[4])+" | Estado: "+f[5]);}
                } else if(tipo.equals("asistencia")){
                    long parcial=Long.parseLong(args.getOrDefault("parcial","0"));
                    long materiaId;
                    String[] materia;
                    if ("true".equals(args.get("general"))) {
                        Academico.exigir(sesion.usuario().admin(), "El reporte general requiere administración.");
                        materiaId = 0;
                        materia = new String[]{"0","general","Todas las asignaturas","Todos los parciales","0"};
                    } else if(parcial>0){
                        materia=bd.uno("SELECT m.id,m.codigo,m.nombre,p.nombre,m.docente_id FROM parcial p JOIN materia m ON m.id=p.materia_id WHERE p.id=?",parcial);
                        Academico.exigir(materia!=null,"Selecciona un parcial válido.");materiaId=Long.parseLong(materia[0]);
                    } else {
                        materiaId=Long.parseLong(args.getOrDefault("materia","0"));
                        materia=bd.uno("SELECT id,codigo,nombre,'Todos los parciales',docente_id FROM materia WHERE id=?",materiaId);
                        Academico.exigir(materia!=null,"Selecciona una materia válida.");
                    }
                    boolean estudiante=sesion.usuario().estudiante();
                    Academico.exigir(estudiante||sesion.usuario().admin()||Long.parseLong(materia[4])==sesion.usuario().id(),"No tienes acceso a este reporte.");
                    if(estudiante) Academico.exigir(bd.uno("SELECT estudiante_id FROM matricula WHERE materia_id=? AND estudiante_id=?",materiaId,sesion.usuario().id())!=null,"No tienes acceso a este reporte.");
                    String paralelo = args.getOrDefault("paralelo", "").strip();
                    if (!paralelo.isEmpty()) paralelo = Reservas.paralelo(paralelo);
                    titulo="Reporte de asistencia";archivo="asistencia-"+materia[1]+(parcial>0?"-parcial-"+parcial:"")+".pdf";
                    lineas.add("Asignatura: "+materia[1]+" · "+materia[2]);lineas.add("Parcial: "+materia[3]);
                    if (!paralelo.isEmpty()) lineas.add("Paralelo: " + paralelo);
                    lineas.add("Generado: "+reloj.instant().atZone(reloj.getZone()).toLocalDateTime().toString().replace('T',' '));lineas.add("");
                    var rows=bd.consultar("""
                            SELECT DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),c.tema,s.usuario,
                                CONCAT_WS(' ',s.nombre,s.apellidos),c.paralelo,
                                CASE WHEN c.fin>? THEN 'PROGRAMADA'
                                     WHEN a.estado='ASISTENCIA' THEN 'ASISTENCIA'
                                     WHEN a.estado='ATRASO' THEN 'ATRASO'
                                     WHEN j.motivo IS NOT NULL THEN 'FALTA JUSTIFICADA'
                                     ELSE 'FALTA' END,
                                DATE_FORMAT(a.fecha,'%Y-%m-%d %H:%i'),m.codigo,p.nombre
                            FROM clase c JOIN parcial p ON p.id=c.parcial_id
                            JOIN materia m ON m.id=p.materia_id JOIN matricula e ON e.materia_id=m.id
                            JOIN cuenta s ON s.id=e.estudiante_id
                            LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=s.id
                            LEFT JOIN justificacion j ON j.clase_id=c.id AND j.estudiante_id=s.id
                            WHERE (?=0 OR m.id=?) AND (?=0 OR c.parcial_id=?)
                                AND (?='' OR c.paralelo=?) AND c.cancelada=FALSE AND e.estado IN ('APROBADA','RETIRADA')
                                AND c.paralelo=COALESCE(e.paralelo,'A')
                                AND e.desde<=DATE(c.inicio) AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
                                AND (?=FALSE OR e.estudiante_id=?)
                            ORDER BY c.inicio,s.apellidos,s.nombre
                            """,LocalDateTime.ofInstant(reloj.instant(),reloj.getZone()),materiaId,materiaId,parcial,parcial,paralelo,paralelo,estudiante,estudiante?sesion.usuario().id():0);
                    int[] totales = new int[5];
                    for (String[] fila : rows) {
                        int indice = switch (fila[5]) {
                            case "ASISTENCIA" -> 1;
                            case "ATRASO" -> 2;
                            case "FALTA JUSTIFICADA" -> 3;
                            case "FALTA" -> 4;
                            default -> 0;
                        };
                        if (indice != 0) { totales[0]++; totales[indice]++; }
                    }
                    var academico = new Academico(bd, LocalDateTime.ofInstant(reloj.instant(), reloj.getZone()));
                    var resumen = academico.resumen(Arrays.stream(totales).mapToObj(Integer::toString).toArray(String[]::new));
                    lineas.add("Clases computadas por estudiante: " + resumen[0] + " | Asistencia: " + resumen[5] + "%");
                    lineas.add("Asistencias: " + resumen[1] + " | Atrasos: " + resumen[2]
                            + " | Faltas: " + resumen[3] + " | Justificadas: " + resumen[4]);
                    lineas.add("");
                    lineas.add("FECHA | ASIGNATURA | ESTUDIANTE | PARALELO | ESTADO | REGISTRO");
                    for(String[] f:rows)lineas.add(f[0]+" | "+f[7]+" | "+f[3]+" | "+f[4]+" | "+f[5]+" | "+(f[6].isBlank()?"—":f[6]));
                } else {responderTexto(intercambio,400,"Tipo de reporte no válido.");return;}
            } catch(NumberFormatException e){responderTexto(intercambio,400,"Falta un identificador de reporte válido.");return;}
            byte[] contenido=PdfBasico.generar(titulo,lineas);
            var headers=intercambio.getResponseHeaders();
            headers.set("Content-Type","application/pdf");
            headers.set("Content-Disposition","attachment; filename=\""+archivo.replaceAll("[^A-Za-z0-9._-]","_")+"\"");
            headers.set("Cache-Control","no-store");headers.set("X-Content-Type-Options","nosniff");
            intercambio.sendResponseHeaders(200,contenido.length);intercambio.getResponseBody().write(contenido);
        } catch(SQLException|IllegalArgumentException e){
            responderTexto(intercambio,403,"No se pudo generar el reporte solicitado.");
        } finally { intercambio.close(); }
    }

    private static void responderTexto(HttpExchange intercambio, int codigo, String texto) throws IOException {
        byte[] contenido = texto.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        intercambio.getResponseHeaders().set("Cache-Control", "no-store");
        intercambio.sendResponseHeaders(codigo, contenido.length);
        intercambio.getResponseBody().write(contenido);
    }

    private void atender(HttpExchange intercambio) throws IOException {
        try (intercambio) {
            if (!intercambio.getRequestMethod().equals("POST") || !intercambio.getRequestURI().getPath().equals(Protocolo.RUTA)) {
                intercambio.sendResponseHeaders(404, -1);
                return;
            }
            byte[] respuesta;
            int codigo = 200;
            try {
                var filas = Protocolo.leer(intercambio.getRequestBody());
                Academico.exigir(filas.size() == 1 && filas.get(0).length >= 2, "Solicitud inválida.");
                respuesta = Protocolo.codificar(ejecutar(filas.get(0)).toArray(String[][]::new));
            } catch (java.time.DateTimeException | NumberFormatException e) {
                codigo = 400;
                respuesta = Protocolo.codificar(new String[]{"Revisa las fechas, horas y campos numéricos."});
            } catch (IllegalArgumentException | IOException e) {
                codigo = 400;
                respuesta = Protocolo.codificar(new String[]{Objects.toString(e.getMessage(), "Solicitud inválida.")});
            } catch (SQLException e) {
                if (e.getErrorCode() == 1062) {
                    codigo = 409;
                    respuesta = Protocolo.codificar(new String[]{"Ya existe un registro con esos datos."});
                } else {
                    System.err.println("Error de MariaDB. SQLState=" + e.getSQLState() + " código=" + e.getErrorCode());
                    codigo = 500;
                    respuesta = Protocolo.codificar(new String[]{"La base de datos no pudo completar la operación."});
                }
            } catch (RuntimeException e) {
                System.err.println("Error interno al procesar la solicitud: " + e.getClass().getSimpleName());
                codigo = 500;
                respuesta = Protocolo.codificar(new String[]{"El servidor no pudo completar la operación."});
            }
            intercambio.getResponseHeaders().set("Content-Type", "application/octet-stream");
            intercambio.getResponseHeaders().set("Cache-Control", "no-store");
            intercambio.sendResponseHeaders(codigo, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
        }
    }
}
