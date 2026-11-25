package ec.asistencia;

import java.io.*;
import java.sql.SQLException;
import java.time.*;
import java.time.format.*;
import java.util.*;
import javax.imageio.ImageIO;

final class Academico {
    private static final String FUERA_PARCIALES = "Fuera de parciales";
    record Usuario(long id, String rol, String nombre) {
        boolean admin() { return rol.equals("ADMIN"); }
        boolean estudiante() { return rol.equals("ESTUDIANTE"); }
    }

    static final DateTimeFormatter HORARIO = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    static final List<String> PARCIALES_HABITUALES = List.of("Primer parcial", "Segundo parcial");
    static final int TOLERANCIA_PREDETERMINADA = 5;
    static final int ESTADO_DETALLE = 7;
    static final int MOTIVO_DETALLE = 9;
    private static final String VIGENCIA = """
            e.estado IN ('APROBADA','RETIRADA')
            AND c.paralelo=COALESCE(e.paralelo,'A')
            AND e.desde<=DATE(c.inicio) AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
            """;
    private static final String DETALLE = """
            SELECT c.id, s.id, s.usuario, CONCAT_WS(' ',s.nombre,s.apellidos), c.tema, DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),
                DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'),
                CASE WHEN a.estado='ASISTENCIA' THEN 'A tiempo'
                     WHEN a.estado='ATRASO' THEN 'Atraso'
                     WHEN c.fin<=? THEN IF(j.motivo IS NULL, 'Falta sin justificar', 'Falta justificada')
                     WHEN c.inicio<=? THEN 'En curso sin marca'
                     ELSE 'Programada' END,
                DATE_FORMAT(a.fecha,'%Y-%m-%d %H:%i:%s'), j.motivo,c.paralelo
            FROM clase c JOIN parcial p ON p.id=c.parcial_id
            JOIN matricula e ON e.materia_id=p.materia_id
            JOIN cuenta s ON s.id=e.estudiante_id
            LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=s.id
            LEFT JOIN justificacion j ON j.clase_id=c.id AND j.estudiante_id=s.id
            WHERE c.parcial_id=? AND c.cancelada=FALSE AND
            """ + VIGENCIA + " ORDER BY c.inicio,s.nombre,s.apellidos,s.usuario";

    private final Base bd;
    private final LocalDateTime ahora;
    private final Reservas reservas;

    Academico(Base bd, LocalDateTime ahora) {
        this.bd = bd;
        this.ahora = ahora;
        this.reservas = new Reservas(bd, ahora);
    }

    static void exigir(boolean condicion, String mensaje) {
        if (!condicion) throw new IllegalArgumentException(mensaje);
    }

    static String texto(String valor, int maximo, String campo) {
        String limpio = valor.strip();
        exigir(!limpio.isEmpty() && limpio.length() <= maximo, "Revisa " + campo + " (máximo " + maximo + " caracteres).");
        return limpio;
    }

    static String usuario(String valor, boolean estudiante) {
        String limpio = valor.strip().toLowerCase(Locale.ROOT);
        exigir(limpio.matches(estudiante ? "[0-9]{10}" : "[a-z0-9._-]{3,64}"),
                estudiante ? "Ingresa una cédula de 10 dígitos." : "Usa de 3 a 64 letras, números, puntos, guiones o guiones bajos.");
        return limpio;
    }

    static String usuarioInstitucional(String valor) {
        String limpio = valor.strip().toLowerCase(Locale.ROOT);
        if (limpio.endsWith("@uta.edu.ec")) limpio = limpio.substring(0, limpio.length() - "@uta.edu.ec".length());
        return usuario(limpio, false);
    }

    private static long id(String valor) {
        long n = Long.parseLong(valor);
        exigir(n > 0, "Selecciona un registro.");
        return n;
    }

    private static List<String[]> mensaje(String mensaje) { return Collections.singletonList(new String[]{mensaje}); }

    private void rol(Usuario u, boolean permitido) {
        exigir(u != null && permitido, "Tu cuenta no tiene permiso para esta acción.");
    }

    private long parcial(Usuario u, String valor) throws SQLException {
        long p = id(valor);
        var fila = bd.uno("""
                SELECT m.docente_id FROM parcial p JOIN materia m ON m.id=p.materia_id WHERE p.id=?
                """, p);
        exigir(fila != null && (u.admin() || Long.parseLong(fila[0]) == u.id()), "El parcial no está disponible para tu cuenta.");
        return p;
    }

    private void cuenta(long id, String rol) throws SQLException {
        exigir(bd.uno("SELECT id FROM cuenta WHERE id=? AND rol=?", id, rol) != null, "La cuenta seleccionada no es válida.");
    }

    private void crearPerfil(long id, String rol) throws SQLException {
        if (rol.equals("ESTUDIANTE")) {
            var malla = bd.uno("SELECT id FROM malla_curricular WHERE estado='ACTIVA' ORDER BY es_demostracion,id LIMIT 1");
            exigir(malla != null, "Activa una malla curricular antes de crear estudiantes.");
            bd.cambiar("INSERT IGNORE INTO perfil_estudiante(estudiante_id,malla_id) VALUES(?,?)", id, malla[0]);
        } else if (rol.equals("DOCENTE")) {
            bd.cambiar("INSERT IGNORE INTO perfil_docente(docente_id) VALUES(?)", id);
        }
    }

    private void materia(long id) throws SQLException {
        exigir(bd.uno("SELECT id FROM materia WHERE id=?", id) != null, "La materia seleccionada no existe.");
    }

    private void guardarCuenta(String[] d) throws SQLException {
        boolean estudiante = d[1].equals("ESTUDIANTE");
        exigir(estudiante || d[1].equals("DOCENTE"), "Selecciona Estudiante o Docente.");
        String usuario = usuario(d[2], estudiante);
        long existente = d[0].isEmpty() ? 0 : id(d[0]);
        exigir(bd.uno("SELECT id FROM cuenta WHERE usuario_institucional=? AND rol=? AND id<>?",
                usuario, d[1], existente) == null, "Ese usuario ya está asignado como acceso institucional.");
        String nombres = texto(d[3], 120, "los nombres completos");
        String apellidos = texto(d[4], 120, "los apellidos completos");
        if (d[0].isEmpty()) {
            bd.cambiar("INSERT INTO cuenta(usuario,nombre,apellidos,clave,rol) VALUES(?,?,?,?,?)",
                    usuario, nombres, apellidos, Claves.crear(d[5]), d[1]);
            crearPerfil(Long.parseLong(bd.uno("SELECT LAST_INSERT_ID()")[0]), d[1]);
        } else {
            long id = id(d[0]);
            cuenta(id, d[1]);
            crearPerfil(id, d[1]);
            if (d[5].isEmpty()) {
                bd.cambiar("UPDATE cuenta SET usuario=?,nombre=?,apellidos=? WHERE id=?", usuario, nombres, apellidos, id);
            } else {
                bd.cambiar("UPDATE cuenta SET usuario=?,nombre=?,apellidos=?,clave=? WHERE id=?",
                        usuario, nombres, apellidos, Claves.crear(d[5]), id);
            }
        }
        if (d.length >= 12) {
            long cuentaId = d[0].isEmpty() ? Long.parseLong(bd.uno("SELECT LAST_INSERT_ID()")[0]) : id(d[0]);
            if (d.length == 13) {
                String alias = d[12].isBlank() ? null : usuarioInstitucional(d[12]);
                exigir(alias == null || !alias.matches("[0-9]{10}"), "El usuario institucional debe distinguirse de una cédula.");
                exigir(alias == null || bd.uno("SELECT id FROM cuenta WHERE (usuario_institucional=? OR (usuario=? AND rol=?)) AND id<>?",
                        alias, alias, d[1], cuentaId) == null,
                        "El usuario institucional ya está asignado.");
                bd.cambiar("UPDATE cuenta SET usuario_institucional=? WHERE id=?", alias, cuentaId);
            }
            if (estudiante) {
                String codigo = d[6].isBlank() ? null : texto(d[6],32,"el código estudiantil");
                String correo = validarCorreo(d[7]);
                String telefono = d[8].strip();
                String malla = d[9].strip();
                String nivel = d[10].strip();
                String estado = d[11].isBlank() ? "ACTIVO" : d[11];
                exigir(Set.of("ACTIVO","INACTIVO","EGRESADO","RETIRADO").contains(estado), "Estado académico inválido.");
                if (!malla.isEmpty()) exigir(bd.uno("SELECT id FROM malla_curricular WHERE id=?", id(malla)) != null, "Selecciona una malla válida.");
                if (!nivel.isEmpty()) exigir(Integer.parseInt(nivel) >= 1 && Integer.parseInt(nivel) <= 16, "El nivel debe estar entre 1 y 16.");
                bd.cambiar("UPDATE perfil_estudiante SET codigo_estudiantil=COALESCE(?,codigo_estudiantil),correo=?,telefono=?,malla_id=?,nivel_referencia=?,estado=? WHERE estudiante_id=?",
                        codigo,correo,telefono.isBlank()?null:texto(telefono,32,"el teléfono"),
                        malla.isBlank()?null:id(malla),nivel.isBlank()?null:Integer.parseInt(nivel),estado,cuentaId);
            } else {
                String correo=validarCorreo(d[7]);
                String telefono=d[8].strip();
                bd.cambiar("UPDATE perfil_docente SET correo=?,telefono=?,activo=? WHERE docente_id=?",
                        correo,telefono.isBlank()?null:texto(telefono,32,"el teléfono"),!d[11].equals("INACTIVO"),cuentaId);
            }
        }
    }

    private static String validarCorreo(String valor) {
        if (valor == null || valor.isBlank()) return null;
        String correo=valor.strip();
        exigir(correo.length()<=254 && correo.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"), "Ingresa un correo electrónico válido.");
        return correo;
    }

    private String valorConfiguracion(String clave,String predeterminado)throws SQLException{
        var f=bd.uno("SELECT valor FROM configuracion_academica WHERE clave=?",clave);
        return f==null?predeterminado:f[0];
    }

    private void guardarConfiguracionAcademica(String[] d)throws SQLException{
        validarDecimalConfig(d[0],"0","200","El máximo de créditos debe quedar entre 0 y 200.");
        exigir(d[1].equals("true")||d[1].equals("false"),"Elige si se permite repetir materias aprobadas.");
        validarDecimalConfig(d[2],"0","100","El umbral de asistencia debe quedar entre 0 y 100.");
        exigir(d[3].equals("true")||d[3].equals("false"),"Elige si los atrasos cuentan como asistencia.");
        exigir(d[4].equals("true")||d[4].equals("false"),"Elige cómo tratar las faltas justificadas.");
        String[] claves={"max_creditos_matricula","repetir_materia_aprobada","asistencia_umbral_porcentaje","asistencia_atraso_cuenta","asistencia_justificada_excluida"};
        for(int i=0;i<d.length;i++)bd.cambiar("INSERT INTO configuracion_academica(clave,valor) VALUES(?,?) ON DUPLICATE KEY UPDATE valor=VALUES(valor)",claves[i],d[i]);
    }

    private static void validarDecimalConfig(String valor,String minimo,String maximo,String error){
        if(valor.isBlank())return;
        try{double n=Double.parseDouble(valor);exigir(Double.isFinite(n)&&n>=Double.parseDouble(minimo)&&n<=Double.parseDouble(maximo),error);}
        catch(NumberFormatException e){throw new IllegalArgumentException(error);}
    }

    private void guardarMalla(String[] d) throws SQLException {
        String nombre=texto(d[1],120,"el nombre de la malla");
        String version=texto(d[2],40,"la versión");
        String estado=d[5].isBlank()?"ACTIVA":d[5];
        exigir(Set.of("ACTIVA","INACTIVA").contains(estado),"Estado de malla inválido.");
        LocalDate desde=d[3].isBlank()?null:LocalDate.parse(d[3]);
        LocalDate hasta=d[4].isBlank()?null:LocalDate.parse(d[4]);
        exigir(desde==null||hasta==null||!hasta.isBefore(desde),"La vigencia final debe ser igual o posterior a la inicial.");
        if(d[0].isBlank()) bd.cambiar("INSERT INTO malla_curricular(nombre,version,vigente_desde,vigente_hasta,estado,es_demostracion) VALUES(?,?,?,?,?,FALSE)",nombre,version,desde,hasta,estado);
        else bd.cambiar("UPDATE malla_curricular SET nombre=?,version=?,vigente_desde=?,vigente_hasta=?,estado=? WHERE id=?",nombre,version,desde,hasta,estado,id(d[0]));
    }

    private void guardarCatalogo(String[] d) throws SQLException {
        String codigo=texto(d[2],24,"el código de la materia");
        String nombre=texto(d[3],120,"el nombre de la materia");
        Integer nivel=d[4].isBlank()?null:Integer.valueOf(d[4]);
        String creditos=d[5].isBlank()?null:d[5];
        boolean activa=Boolean.parseBoolean(d[6]);
        exigir(nivel==null||(nivel>=1&&nivel<=16),"El nivel debe estar entre 1 y 16.");
        if(creditos!=null) exigir(Double.parseDouble(creditos)>=0,"Los créditos no pueden ser negativos.");
        long malla=id(d[1]);
        if(d[0].isBlank()) bd.cambiar("INSERT INTO materia_catalogo(malla_id,codigo,nombre,nivel,creditos,activa) VALUES(?,?,?,?,?,?)",malla,codigo,nombre,nivel,creditos,activa);
        else {
            long materia=id(d[0]);
            bd.cambiar("UPDATE materia_catalogo SET malla_id=?,codigo=?,nombre=?,nivel=?,creditos=?,activa=? WHERE id=?",malla,codigo,nombre,nivel,creditos,activa,materia);
            bd.cambiar("UPDATE materia SET codigo=?,nombre=?,nivel=?,catalogo_id=? WHERE catalogo_id=?",codigo,nombre,nivel,materia,materia);
        }
    }

    private void guardarPrerrequisito(String[] d) throws SQLException {
        long materia=id(d[0]), previo=id(d[1]);
        exigir(materia!=previo,"Una materia no puede ser prerrequisito de sí misma.");
        exigir(bd.uno("SELECT a.id FROM materia_catalogo a JOIN materia_catalogo b ON b.id=? WHERE a.id=? AND a.malla_id=b.malla_id",materia,previo)!=null,
                "El prerrequisito debe pertenecer a la misma malla curricular.");
        if(d[2].equals("AGREGAR")) {
            exigir(bd.uno("""
                    WITH RECURSIVE cadena(id) AS (
                        SELECT prerrequisito_id FROM materia_prerrequisito WHERE materia_id=?
                        UNION DISTINCT
                        SELECT p.prerrequisito_id FROM materia_prerrequisito p JOIN cadena ON cadena.id=p.materia_id
                    )
                    SELECT id FROM cadena WHERE id=?
                    """, previo, materia) == null, "El prerrequisito crearía un ciclo.");
            bd.cambiar("INSERT IGNORE INTO materia_prerrequisito(materia_id,prerrequisito_id) VALUES(?,?)",materia,previo);
        }
        else if(d[2].equals("QUITAR")) bd.cambiar("DELETE FROM materia_prerrequisito WHERE materia_id=? AND prerrequisito_id=?",materia,previo);
        else throw new IllegalArgumentException("Operación de prerrequisito inválida.");
    }

    private String siguienteCodigoMateria() throws SQLException {
        String numero = bd.uno("""
                SELECT COALESCE(MAX(CAST(codigo AS DECIMAL(24,0))),0)+1
                FROM materia WHERE codigo REGEXP '^[0-9]{1,24}$'
                """)[0];
        exigir(numero.length() <= 24, "Se agotaron los códigos numéricos de materias.");
        return "0".repeat(Math.max(0, 3 - numero.length())) + numero;
    }

    private long sincronizarCatalogo(String codigo, String nombre, Integer nivel) throws SQLException {
        var catalogo = bd.uno("SELECT c.id FROM materia_catalogo c JOIN malla_curricular m ON m.id=c.malla_id WHERE c.codigo=? AND m.estado='ACTIVA' ORDER BY m.es_demostracion,c.id LIMIT 1", codigo);
        if (catalogo != null) {
            bd.cambiar("UPDATE materia_catalogo SET nombre=?,nivel=?,activa=TRUE WHERE id=?", nombre, nivel, id(catalogo[0]));
            return id(catalogo[0]);
        }
        var malla = bd.uno("SELECT id FROM malla_curricular WHERE estado='ACTIVA' ORDER BY es_demostracion,id LIMIT 1");
        exigir(malla != null, "Crea o activa una malla curricular antes de registrar materias.");
        bd.cambiar("INSERT INTO materia_catalogo(malla_id,codigo,nombre,nivel) VALUES(?,?,?,?)", id(malla[0]), codigo, nombre, nivel);
        return id(bd.uno("SELECT id FROM materia_catalogo WHERE malla_id=? AND codigo=?", id(malla[0]), codigo)[0]);
    }

    private void guardarMateria(String[] d) throws SQLException {
        String nombre = d.length == 8 ? "" : texto(d[2], 120, "la materia");
        long docente = id(d[3]);
        cuenta(docente, "DOCENTE");
        exigir(d.length == 6 || d.length == 8, "Selecciona el período académico de la oferta.");
        long periodo = id(d[5]);
        exigir(bd.uno("SELECT id FROM semestre_academico WHERE id=? FOR UPDATE", periodo) != null,
                "Selecciona un período académico válido.");
        Integer nivel = null;
        if (!d[4].isBlank()) {
            nivel = Integer.parseInt(d[4]);
            exigir(nivel >= 1 && nivel <= 16, "El semestre de la materia debe estar entre 1 y 16.");
        }
        String codigo = d[1].isBlank() ? siguienteCodigoMateria() : texto(d[1],24,"el código de la materia");
        exigir(codigo.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,23}"), "El código solo puede incluir letras, números, puntos, guiones y guiones bajos.");
        boolean activa = d.length == 6 || d[7].equals("true");
        long catalogo;
        if (d.length == 8) {
            catalogo = id(d[6]);
            var oficial = bd.uno("SELECT codigo,nombre,nivel FROM materia_catalogo WHERE id=?", catalogo);
            exigir(oficial != null, "Selecciona una asignatura del catálogo.");
            codigo = oficial[0];
            nombre = oficial[1];
            nivel = oficial[2].isBlank() ? null : Integer.valueOf(oficial[2]);
        } else {
            catalogo = sincronizarCatalogo(codigo,nombre,nivel);
        }
        if (d[0].isEmpty()) {
            bd.cambiar("INSERT INTO materia(codigo,nombre,docente_id,nivel,periodo_id,catalogo_id,activa) VALUES(?,?,?,?,?,?,?)",
                    codigo, nombre, docente, nivel, periodo, catalogo, activa);
            long oferta = Long.parseLong(bd.uno("SELECT LAST_INSERT_ID()")[0]);
            bd.cambiar("INSERT INTO materia_paralelo(materia_id,codigo,cupo_maximo) VALUES(?, 'A',30),(?, 'B',30)",oferta,oferta);
            aplicarParcialesPeriodo(oferta, periodo);
        } else {
            long materia = id(d[0]);
            var actual = bd.uno("SELECT periodo_id,codigo,catalogo_id,docente_id FROM materia WHERE id=? FOR UPDATE", materia);
            exigir(actual != null, "La oferta seleccionada ya no existe.");
            if (!actual[2].equals(Long.toString(catalogo))) {
                exigir(bd.uno("SELECT 1 FROM matricula WHERE materia_id=? LIMIT 1", materia)==null,
                        "No cambies la asignatura de una oferta con matrículas.");
            }
            if (!actual[0].equals(Long.toString(periodo))) {
                exigir(bd.uno("SELECT materia_id FROM matricula WHERE materia_id=? LIMIT 1", materia) == null,
                        "No cambies el período de una oferta con matrículas. Crea otra oferta para el nuevo período.");
                exigir(bd.uno("SELECT 1 FROM clase c JOIN parcial p ON p.id=c.parcial_id WHERE p.materia_id=? LIMIT 1", materia)==null,
                        "Una oferta con clases debe permanecer en su período.");
                if (!actual[0].isBlank()) {
                    bd.cambiar("""
                            DELETE p FROM parcial p JOIN parcial_periodo pp ON pp.nombre=p.nombre AND pp.periodo_id=?
                            WHERE p.materia_id=?
                            """, id(actual[0]), materia);
                }
                var fechas = bd.uno("SELECT MIN(desde),MAX(hasta) FROM parcial WHERE materia_id=? HAVING COUNT(*)>0", materia);
                if (fechas != null) {
                    exigir(bd.uno("SELECT id FROM semestre_academico WHERE id=? AND desde<=? AND hasta>=?",
                            periodo, fechas[0], fechas[1]) != null,
                            "El período nuevo debe contener las fechas de todos los parciales de la oferta.");
                }
            }
            bd.cambiar("UPDATE materia SET codigo=?,nombre=?,docente_id=?,nivel=?,periodo_id=?,catalogo_id=? WHERE id=?",
                    codigo,nombre,docente,nivel,periodo,catalogo,materia);
            aplicarParcialesPeriodo(materia, periodo);
        }
    }

    private void aplicarParcialesPeriodo(long materia, long periodo) throws SQLException {
        bd.cambiar("""
                INSERT INTO parcial(materia_id,nombre,desde,hasta)
                SELECT ?,nombre,desde,hasta FROM parcial_periodo WHERE periodo_id=?
                ON DUPLICATE KEY UPDATE desde=VALUES(desde),hasta=VALUES(hasta)
                """, materia, periodo);
    }

    private void guardarParcialPeriodo(long periodo, String nombre, LocalDate desde, LocalDate hasta) throws SQLException {
        exigir(PARCIALES_HABITUALES.contains(nombre), "Elige Primer parcial o Segundo parcial.");
        exigir(!hasta.isBefore(desde), "La fecha final debe ser igual o posterior a la inicial.");
        var fechas = bd.uno("SELECT desde,hasta FROM semestre_academico WHERE id=? FOR UPDATE", periodo);
        exigir(fechas != null, "Selecciona un período académico válido.");
        exigir(!desde.isBefore(LocalDate.parse(fechas[0])) && !hasta.isAfter(LocalDate.parse(fechas[1])),
                "El parcial debe quedar dentro de las fechas del período.");
        var otro = bd.uno("SELECT desde,hasta FROM parcial_periodo WHERE periodo_id=? AND nombre<>?", periodo, nombre);
        if (otro != null) {
            boolean primero = nombre.equals(PARCIALES_HABITUALES.get(0));
            exigir(primero ? hasta.isBefore(LocalDate.parse(otro[0])) : desde.isAfter(LocalDate.parse(otro[1])),
                    "El primer parcial debe terminar antes de que inicie el segundo.");
        }
        bd.consultar("""
                SELECT p.id FROM parcial p JOIN materia m ON m.id=p.materia_id
                WHERE m.periodo_id=? AND p.nombre=? FOR UPDATE
                """, periodo, nombre);
        exigir(bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id
                WHERE m.periodo_id=? AND p.nombre=? AND (DATE(c.inicio)<? OR DATE(c.fin)>?)
                  AND (c.horario_oferta_id IS NULL OR c.inicio<=?) LIMIT 1
                """, periodo, nombre, desde, hasta, ahora) == null,
                "Hay clases registradas que quedarían fuera de esas fechas.");
        bd.cambiar("""
                INSERT INTO parcial_periodo(periodo_id,nombre,desde,hasta) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE desde=VALUES(desde),hasta=VALUES(hasta)
                """, periodo, nombre, desde, hasta);
        bd.cambiar("""
                INSERT INTO parcial(materia_id,nombre,desde,hasta)
                SELECT id,?,?,? FROM materia WHERE periodo_id=?
                ON DUPLICATE KEY UPDATE desde=VALUES(desde),hasta=VALUES(hasta)
                """, nombre, desde, hasta, periodo);
        for (var oferta : bd.consultar("SELECT id FROM materia WHERE periodo_id=? ORDER BY id", periodo)) {
            sincronizarClases(id(oferta[0]));
        }
    }

    private void guardarParcial(String[] d) throws SQLException {
        long materia = id(d[0]);
        materia(materia);
        String nombre = texto(d[1], 60, "el nombre del parcial");
        exigir(!FUERA_PARCIALES.equalsIgnoreCase(nombre), "El rango Fuera de parciales se calcula desde el período.");
        LocalDate desde = LocalDate.parse(d[2]);
        LocalDate hasta = LocalDate.parse(d[3]);
        exigir(!hasta.isBefore(desde), "La fecha final del parcial debe ser igual o posterior a la inicial.");
        var oferta = bd.uno("SELECT periodo_id FROM materia WHERE id=?", materia);
        if (oferta != null && !oferta[0].isEmpty() && PARCIALES_HABITUALES.contains(nombre)) {
            guardarParcialPeriodo(id(oferta[0]), nombre, desde, hasta);
            return;
        }
        if (oferta != null && !oferta[0].isEmpty()) {
            exigir(bd.uno("SELECT id FROM semestre_academico WHERE id=? AND desde<=? AND hasta>=?", oferta[0], desde, hasta) != null,
                    "El parcial debe quedar dentro del período.");
        } else if (bd.uno("SELECT id FROM semestre_academico LIMIT 1") != null) {
            exigir(bd.uno("SELECT id FROM semestre_academico WHERE desde<=? AND hasta>=?", desde, hasta) != null,
                    "El parcial debe quedar dentro de un período académico configurado.");
        }
        var existente = bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=?", materia, nombre);
        long parcial = existente == null ? 0 : id(existente[0]);
        exigir(parcial != 0 || PARCIALES_HABITUALES.contains(nombre), "Elige Primer parcial o Segundo parcial.");
        exigir(bd.uno("SELECT id FROM parcial WHERE materia_id=? AND id<>? AND desde<=? AND hasta>=?", materia, parcial, hasta, desde) == null,
                "Las fechas se cruzan con otro parcial de esta materia.");
        if (nombre.equalsIgnoreCase(PARCIALES_HABITUALES.get(0))) {
            exigir(bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=? AND desde<=?",
                    materia, PARCIALES_HABITUALES.get(1), hasta) == null, "El primer parcial debe terminar antes de que inicie el segundo.");
        } else if (nombre.equalsIgnoreCase(PARCIALES_HABITUALES.get(1))) {
            exigir(bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=? AND hasta>=?",
                    materia, PARCIALES_HABITUALES.get(0), desde) == null, "El segundo parcial debe empezar después de que termine el primero.");
        }
        if (parcial == 0) {
            bd.cambiar("INSERT INTO parcial(materia_id,nombre,desde,hasta) VALUES(?,?,?,?)", materia, nombre, desde, hasta);
        } else {
            exigir(bd.uno("SELECT id FROM clase WHERE parcial_id=? AND (DATE(inicio)<? OR DATE(fin)>?)",
                    parcial, desde, hasta) == null, "Hay clases ya programadas que quedarían fuera de esas fechas.");
            bd.cambiar("UPDATE parcial SET desde=?,hasta=? WHERE id=?", desde, hasta, parcial);
        }
    }

    void sincronizarClases(long oferta) throws SQLException {
        var periodo = bd.uno("""
                SELECT s.id,s.desde,s.hasta FROM semestre_academico s
                JOIN materia m ON m.periodo_id=s.id WHERE m.id=? FOR UPDATE
                """, oferta);
        if (periodo == null) return;
        LocalDate inicioPeriodo = LocalDate.parse(periodo[1]);
        LocalDate finPeriodo = LocalDate.parse(periodo[2]);
        bd.cambiar("UPDATE parcial SET desde=?,hasta=? WHERE materia_id=? AND nombre=?",
                inicioPeriodo, finPeriodo, oferta, FUERA_PARCIALES);
        var parciales = bd.consultar("""
                SELECT p.id,pp.desde,pp.hasta FROM parcial_periodo pp
                JOIN parcial p ON p.materia_id=? AND p.nombre=pp.nombre WHERE pp.periodo_id=?
                """, oferta, id(periodo[0]));
        bd.cambiar("""
                UPDATE clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN materia m ON m.id=p.materia_id
                LEFT JOIN oferta_horario h ON h.id=c.horario_oferta_id AND h.materia_id=m.id

                SET c.cancelada=TRUE
                WHERE m.id=? AND c.horario_oferta_id IS NOT NULL AND c.inicio>? AND NOT c.cancelada
                  AND (h.id IS NULL OR NOT h.activo OR DATE(c.inicio) NOT BETWEEN ? AND ?
                    OR WEEKDAY(c.inicio)+1<>h.dia_semana OR c.paralelo<>h.paralelo
                    OR TIME(c.inicio)<>h.hora_inicio
                    OR p.nombre<>COALESCE((SELECT pp.nombre FROM parcial_periodo pp
                        WHERE pp.periodo_id=m.periodo_id AND DATE(c.inicio) BETWEEN pp.desde AND pp.hasta
                        ORDER BY pp.desde LIMIT 1),?))
                """, oferta, ahora, inicioPeriodo, finPeriodo, FUERA_PARCIALES);
        var bloques = bd.consultar("""
                SELECT id,paralelo,dia_semana,hora_inicio,hora_fin,COALESCE(aula,'')
                FROM oferta_horario WHERE materia_id=? AND activo ORDER BY dia_semana,hora_inicio
                """, oferta);
        LocalDate desde = inicioPeriodo.isBefore(ahora.toLocalDate()) ? ahora.toLocalDate() : inicioPeriodo;
        long fueraDeParciales = 0;
        for (var bloque : bloques) {
            long horario = id(bloque[0]);
            String grupo = bloque[1];
            var dia = DayOfWeek.of(Integer.parseInt(bloque[2]));
            LocalTime inicio = LocalTime.parse(bloque[3]);
            LocalTime fin = LocalTime.parse(bloque[4]);
            String aula = bloque[5].isBlank() ? null : bloque[5];
            int tolerancia = (int)Math.max(0, Math.min(TOLERANCIA_PREDETERMINADA, Duration.between(inicio,fin).toMinutes()-1));
            for (LocalDate fecha = desde.with(java.time.temporal.TemporalAdjusters.nextOrSame(dia));
                 !fecha.isAfter(finPeriodo); fecha = fecha.plusWeeks(1)) {
                LocalDateTime empieza = fecha.atTime(inicio);
                if (!empieza.isAfter(ahora)) continue;
                LocalDateTime termina = fecha.atTime(fin);
                long parcial = 0;
                for (var rango : parciales) {
                    if (!fecha.isBefore(LocalDate.parse(rango[1])) && !fecha.isAfter(LocalDate.parse(rango[2]))) {
                        parcial = id(rango[0]);
                        break;
                    }
                }
                if (parcial == 0) {
                    if (fueraDeParciales == 0) {
                        bd.cambiar("""
                                INSERT INTO parcial(materia_id,nombre,desde,hasta) VALUES(?,?,?,?)
                                ON DUPLICATE KEY UPDATE desde=VALUES(desde),hasta=VALUES(hasta)
                                """, oferta, FUERA_PARCIALES, inicioPeriodo, finPeriodo);
                        fueraDeParciales = id(bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=?",
                                oferta, FUERA_PARCIALES)[0]);
                    }
                    parcial = fueraDeParciales;
                }
                var existente = bd.uno("SELECT id,horario_oferta_id,paralelo FROM clase WHERE parcial_id=? AND inicio=? FOR UPDATE",
                        parcial, empieza);
                if (existente != null && existente[1].isBlank() && existente[2].equals(grupo)) continue;
                if (existente == null && bd.uno("""
                        SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                        WHERE p.materia_id=? AND c.horario_oferta_id IS NULL AND NOT c.cancelada
                          AND c.paralelo=? AND c.inicio=? AND c.fin=? LIMIT 1
                        """, oferta, grupo, empieza, termina) != null) continue;
                if (existente == null) {
                    existente = bd.uno("""
                            SELECT c.id,c.horario_oferta_id,c.paralelo FROM clase c JOIN parcial p ON p.id=c.parcial_id
                            WHERE p.materia_id=? AND c.horario_oferta_id=? AND c.paralelo=? AND c.inicio=? AND c.cancelada
                            ORDER BY c.id LIMIT 1 FOR UPDATE
                            """, oferta, horario, grupo, empieza);
                }
                long excluir = existente == null ? 0 : id(existente[0]);
                exigir(existente == null || !existente[1].isBlank(),
                        "Hay una clase manual de otro paralelo en " + empieza + ".");
                exigir(bd.uno("""
                        SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                        JOIN materia anterior ON anterior.id=p.materia_id JOIN materia actual ON actual.id=?
                        WHERE c.id<>? AND NOT c.cancelada AND c.inicio<? AND c.fin>?
                          AND (anterior.docente_id=actual.docente_id OR UPPER(c.aula)=UPPER(?)) LIMIT 1
                        """, oferta, excluir, termina, empieza, aula) == null,
                        "El horario se cruza con una clase ya programada en " + empieza + ".");
                validarCruceEstudiantes(oferta, grupo, empieza, termina, excluir);
                if (existente == null) {
                    bd.cambiar("""
                            INSERT INTO clase(parcial_id,tema,inicio,fin,tolerancia,paralelo,aula,horario_oferta_id)
                            VALUES(?,'Por definir',?,?,?,?,?,?)
                            """, parcial, empieza, termina, tolerancia, grupo, aula, horario);
                } else {
                    bd.cambiar("""
                            UPDATE clase SET parcial_id=?,fin=?,tolerancia=?,paralelo=?,aula=?,horario_oferta_id=?,cancelada=FALSE
                            WHERE id=?
                            """, parcial, termina, tolerancia, grupo, aula, horario, excluir);
                }
            }
        }
    }

    private void validarCruceEstudiantes(long oferta, String grupo, LocalDateTime inicio, LocalDateTime fin,
                                         long excluir) throws SQLException {
        var cruce = bd.uno("""
                SELECT m.codigo,m.nombre,c.paralelo FROM matricula e
                JOIN matricula o ON o.estudiante_id=e.estudiante_id AND o.materia_id<>e.materia_id
                JOIN materia m ON m.id=o.materia_id JOIN parcial p ON p.materia_id=m.id
                JOIN clase c ON c.parcial_id=p.id AND c.paralelo=COALESCE(o.paralelo,'A')
                WHERE e.materia_id=? AND COALESCE(e.paralelo,'A')=? AND c.id<>?
                  AND NOT c.cancelada AND c.inicio<? AND c.fin>?
                  AND e.estado IN ('APROBADA','RETIRADA') AND e.desde<=DATE(?)
                  AND (e.hasta IS NULL OR e.hasta>DATE(?))
                  AND o.estado IN ('APROBADA','RETIRADA') AND o.desde<=DATE(c.inicio)
                  AND (o.hasta IS NULL OR o.hasta>DATE(c.inicio)) LIMIT 1
                """, oferta, grupo, excluir, fin, inicio, inicio, inicio);
        exigir(cruce == null, cruce == null ? "" : "La clase se cruza para estudiantes inscritos en "
                +cruce[0]+" · "+cruce[1]+", paralelo "+cruce[2]+".");
    }

    private String iniciarClaseAhora(Usuario usuario, String[] datos) throws SQLException {
        long oferta = id(datos[0]);
        String paralelo = Reservas.paralelo(datos[1]);
        String tema = texto(datos[2], 120, "el tema");
        var periodo = bd.uno("""
                SELECT s.id,s.desde,s.hasta FROM materia m
                JOIN semestre_academico s ON s.id=m.periodo_id
                WHERE m.id=? AND m.activa AND (? OR m.docente_id=?) FOR UPDATE
                """, oferta, usuario.admin(), usuario.id());
        exigir(periodo != null, "Selecciona una oferta activa asignada a tu cuenta.");
        LocalDate hoy = ahora.toLocalDate();
        exigir(!hoy.isBefore(LocalDate.parse(periodo[1])) && !hoy.isAfter(LocalDate.parse(periodo[2])),
                "La fecha de hoy debe estar dentro del período académico.");
        exigir(bd.uno("SELECT materia_id FROM materia_paralelo WHERE materia_id=? AND codigo=? AND activo",
                oferta, paralelo) != null, "Selecciona un paralelo activo de esta oferta.");
        var abierta = bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                WHERE p.materia_id=? AND c.paralelo=? AND NOT c.cancelada AND c.inicio<=? AND c.fin>?
                ORDER BY c.inicio LIMIT 1 FOR UPDATE
                """, oferta, paralelo, ahora, ahora);
        if (abierta != null) {
            guardarTemaClase(usuario, new String[]{abierta[0],tema});
            return "La clase ya estaba en curso. Tema actualizado.";
        }
        LocalDateTime inicio = ahora.withSecond(0).withNano(0);
        LocalDateTime fin = inicio.plusMinutes(30);
        exigir(fin.toLocalDate().equals(hoy), "La sesión de 30 minutos debe terminar el mismo día.");
        exigir(bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN materia otra ON otra.id=p.materia_id JOIN materia actual ON actual.id=?
                WHERE NOT c.cancelada AND otra.docente_id=actual.docente_id AND c.inicio<? AND c.fin>? LIMIT 1
                """, oferta, fin, inicio) == null, "Tienes otra clase en ese intervalo.");
        validarCruceEstudiantes(oferta, paralelo, inicio, fin, 0);
        var parcial = bd.uno("""
                SELECT p.id FROM parcial p JOIN parcial_periodo pp ON pp.nombre=p.nombre
                WHERE p.materia_id=? AND pp.periodo_id=? AND ? BETWEEN pp.desde AND pp.hasta LIMIT 1
                """, oferta, id(periodo[0]), hoy);
        if (parcial == null) {
            bd.cambiar("""
                    INSERT INTO parcial(materia_id,nombre,desde,hasta) VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE desde=VALUES(desde),hasta=VALUES(hasta)
                    """, oferta, FUERA_PARCIALES, LocalDate.parse(periodo[1]), LocalDate.parse(periodo[2]));
            parcial = bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=?", oferta, FUERA_PARCIALES);
        }
        bd.cambiar("""
                INSERT INTO clase(parcial_id,tema,inicio,fin,tolerancia,paralelo) VALUES(?,?,?,?,?,?)
                """, id(parcial[0]), tema, inicio, fin, TOLERANCIA_PREDETERMINADA, paralelo);
        return "Clase iniciada. La asistencia está abierta durante 30 minutos.";
    }

    private void guardarTemaClase(Usuario usuario, String[] datos) throws SQLException {
        long clase = id(datos[0]);
        exigir(bd.cambiar("""
                UPDATE clase c JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id
                SET c.tema=? WHERE c.id=? AND (? OR m.docente_id=?) AND NOT c.cancelada AND c.fin>?
                """, texto(datos[1],120,"el tema"), clase, usuario.admin(), usuario.id(), ahora) == 1,
                "Selecciona una clase activa de una oferta asignada.");
    }

    private void guardarClase(String[] d) throws SQLException {
        long parcial = id(d[1]);
        var fechas = bd.uno("SELECT desde,hasta,materia_id FROM parcial WHERE id=? FOR UPDATE", parcial);
        exigir(fechas != null, "Selecciona un parcial válido.");
        LocalDateTime inicio = LocalDateTime.parse(d[3], HORARIO);
        LocalDateTime fin = LocalDateTime.parse(d[4], HORARIO);
        int tolerancia = d[5].isBlank() ? TOLERANCIA_PREDETERMINADA : Integer.parseInt(d[5]);
        exigir(fin.isAfter(inicio) && inicio.isAfter(ahora), "Programa una clase futura con fin posterior al inicio.");
        exigir(!inicio.toLocalDate().isBefore(LocalDate.parse(fechas[0]))
                && !fin.toLocalDate().isAfter(LocalDate.parse(fechas[1])), "La clase debe quedar dentro del parcial.");
        exigir(tolerancia >= 0 && inicio.plusMinutes(tolerancia).isBefore(fin), "La tolerancia debe ser menor que la duración.");
        String tema = texto(d[2], 120, "el tema");
        long clase = d[0].isEmpty() ? 0 : id(d[0]);
        String[] anterior = clase == 0 ? null : bd.uno("SELECT paralelo,horario_oferta_id FROM clase WHERE id=?", clase);
        exigir(anterior == null || anterior[1].isBlank(), "Las clases del horario se ajustan desde Ofertas académicas.");
        String grupo = d.length >= 7 ? Reservas.paralelo(d[6]) : anterior == null ? "A" : anterior[0];
        String aula=d.length>=8&&!d[7].isBlank()?texto(d[7],80,"el aula o laboratorio"):null;
        exigir(anterior == null || anterior[0].equals(grupo), "El paralelo de una clase creada no se puede cambiar.");
        exigir(bd.uno("SELECT materia_id FROM materia_paralelo WHERE materia_id=? AND codigo=? AND activo=TRUE",
                Long.parseLong(fechas[2]), grupo) != null, "Configura el paralelo antes de programar sus clases.");
        exigir(inicio.getDayOfWeek().getValue() <= 5, "Programa las clases de lunes a viernes.");
        var jornada = Jornada.de(Jornada.minutos(inicio.toLocalTime()), Jornada.minutos(fin.toLocalTime()));
        exigir(inicio.toLocalDate().equals(fin.toLocalDate()) && jornada != null,
                "Programa la clase completa entre 07:00 y 13:00 o entre 14:00 y 20:00.");
        var periodo = bd.uno("SELECT desde,hasta FROM semestre_academico WHERE desde<=? AND hasta>=?",
                inicio.toLocalDate(), inicio.toLocalDate());
        if (periodo == null) periodo = reservas.fechasPeriodoGeneral();
        LocalDate desde = LocalDate.parse(periodo == null ? fechas[0] : periodo[0]);
        LocalDate hasta = LocalDate.parse(periodo == null ? fechas[1] : periodo[1]);
        var anteriorJornada = reservas.jornadaParalelo(Long.parseLong(fechas[2]), grupo, desde, hasta, clase);
        if (anteriorJornada != null) exigir(anteriorJornada == jornada,
                "El paralelo " + grupo + " ya tiene clases en la jornada de " + anteriorJornada.nombre + ".");
        exigir(bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN materia otra ON otra.id=p.materia_id JOIN materia actual ON actual.id=?
                WHERE c.id<>? AND c.cancelada=FALSE AND otra.docente_id=actual.docente_id
                    AND c.inicio<? AND c.fin>?
                """, Long.parseLong(fechas[2]), clase, fin, inicio) == null, "El horario se cruza con otra clase del docente.");
        if(aula!=null)exigir(bd.uno("SELECT id FROM clase WHERE id<>? AND cancelada=FALSE AND UPPER(aula)=UPPER(?) AND inicio<? AND fin>?",clase,aula,fin,inicio)==null,
                "El aula ya está ocupada en ese horario.");
        validarCruceEstudiantes(Long.parseLong(fechas[2]), grupo, inicio, fin, clase);
        if (clase == 0) {
            bd.cambiar("INSERT INTO clase(parcial_id,tema,inicio,fin,tolerancia,paralelo,aula) VALUES(?,?,?,?,?,?,?)",
                    parcial, tema, inicio, fin, tolerancia, grupo,aula);
        } else {
            exigir(bd.cambiar("""
                    UPDATE clase SET parcial_id=?,tema=?,inicio=?,fin=?,tolerancia=?,aula=?
                    WHERE id=? AND inicio>? AND cancelada=FALSE
                    """, parcial, tema, inicio, fin, tolerancia,aula, clase, ahora) == 1, "Solo se pueden editar clases futuras activas.");
        }
    }

    private void guardarSemestre(String[] d) throws SQLException {
        long semestre = d[0].isEmpty() ? 0 : id(d[0]);
        String nombre = texto(d[1], 80, "el nombre del semestre");
        LocalDate desde = LocalDate.parse(d[2]);
        LocalDate hasta = LocalDate.parse(d[3]);
        exigir(!hasta.isBefore(desde), "El fin del semestre debe ser igual o posterior al inicio.");
        LocalDate matriculaDesde = d.length > 4 && !d[4].isBlank() ? LocalDate.parse(d[4]) : null;
        LocalDate matriculaHasta = d.length > 5 && !d[5].isBlank() ? LocalDate.parse(d[5]) : null;
        String estado = d.length > 6 && !d[6].isBlank() ? d[6] : "MATRICULA_ABIERTA";
        exigir(Set.of("PLANIFICADO", "MATRICULA_ABIERTA", "MATRICULA_CERRADA", "EN_CURSO", "FINALIZADO").contains(estado),
                "Selecciona un estado válido para el período.");
        exigir(matriculaDesde == null || matriculaHasta == null || !matriculaHasta.isBefore(matriculaDesde),
                "El fin de matrícula debe ser igual o posterior a su inicio.");
        exigir(matriculaDesde == null || !matriculaDesde.isAfter(hasta),
                "La matrícula debe abrir como máximo el último día del período.");
        exigir(matriculaHasta == null || !matriculaHasta.isAfter(hasta),
                "El cierre de matrícula no puede ser posterior al fin del período.");
        exigir(bd.uno("SELECT id FROM semestre_academico WHERE id<>? AND desde<=? AND hasta>=?",
                semestre, hasta, desde) == null, "Las fechas se cruzan con otro semestre.");
        if (semestre == 0) {
            bd.cambiar("INSERT INTO semestre_academico(nombre,desde,hasta,matricula_desde,matricula_hasta,estado) VALUES(?,?,?,?,?,?)",
                    nombre, desde, hasta, matriculaDesde, matriculaHasta, estado);
        } else {
            var anterior = bd.uno("SELECT desde,hasta FROM semestre_academico WHERE id=? FOR UPDATE", semestre);
            exigir(anterior != null, "El período seleccionado ya no existe.");
            exigir(bd.uno("""
                    SELECT p.id FROM parcial p JOIN materia m ON m.id=p.materia_id
                    WHERE m.periodo_id=? AND p.nombre<>? AND (p.desde<? OR p.hasta>?) LIMIT 1
                    """, semestre, FUERA_PARCIALES, desde, hasta) == null
                    && bd.uno("""
                            SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                            JOIN materia m ON m.id=p.materia_id
                            WHERE m.periodo_id=? AND p.nombre=? AND (c.inicio<=? OR c.horario_oferta_id IS NULL)
                              AND (DATE(c.inicio)<? OR DATE(c.fin)>?) LIMIT 1
                            """, semestre, FUERA_PARCIALES, ahora, desde, hasta) == null
                    && bd.uno("SELECT periodo_id FROM parcial_periodo WHERE periodo_id=? AND (desde<? OR hasta>?) LIMIT 1",
                            semestre, desde, hasta) == null,
                    "No cambies las fechas de un período si algún parcial quedaría fuera de su rango.");
            bd.cambiar("UPDATE semestre_academico SET nombre=?,desde=?,hasta=?,matricula_desde=?,matricula_hasta=?,estado=? WHERE id=?",
                    nombre, desde, hasta, matriculaDesde, matriculaHasta, estado, semestre);
            if (!anterior[0].equals(desde.toString()) || !anterior[1].equals(hasta.toString())) {
                for (var oferta : bd.consultar("SELECT id FROM materia WHERE periodo_id=? ORDER BY id", semestre)) {
                    sincronizarClases(id(oferta[0]));
                }
            }
        }
    }

    private void matricular(String[] d) throws SQLException {
        long materia = id(d[0]);
        long alumno = id(d[1]);
        materia(materia);
        reservas.bloquearAlumno(alumno);
        bd.uno("SELECT id FROM materia WHERE id=? FOR UPDATE", materia);
        switch (d[2]) {
            case "HABILITAR" -> {
                var fila = bd.uno("SELECT estado FROM matricula WHERE materia_id=? AND estudiante_id=?", materia, alumno);
                String estado = fila == null ? "" : fila[0];
                exigir(Set.of("", "RECHAZADA", "HABILITADA").contains(estado), "El estudiante ya tiene una solicitud o matrícula en esta materia.");
                if (fila == null) {
                    bd.cambiar("INSERT INTO matricula(materia_id,estudiante_id,estado) VALUES(?,?,'HABILITADA')", materia, alumno);
                } else if (estado.equals("RECHAZADA")) {
                    bd.cambiar("""
                            UPDATE matricula SET estado='HABILITADA',foto_matricula=NULL,paralelo=NULL
                            WHERE materia_id=? AND estudiante_id=? AND estado='RECHAZADA'
                            """, materia, alumno);
                }
            }
            case "LEGALIZAR", "APROBAR" -> exigir(bd.cambiar("""
                    UPDATE matricula SET estado='APROBADA',desde=?,hasta=NULL
                    WHERE materia_id=? AND estudiante_id=? AND estado='PENDIENTE' AND paralelo IS NOT NULL
                    """, ahora.toLocalDate(), materia, alumno) == 1,
                    "Para legalizar, el estudiante debe confirmar su solicitud con un paralelo.");
            case "RECHAZAR" -> exigir(bd.cambiar("""
                    UPDATE matricula SET estado='RECHAZADA' WHERE materia_id=? AND estudiante_id=? AND estado IN ('HABILITADA','RESERVADA','PENDIENTE')
                    """, materia, alumno) == 1, "Selecciona una materia habilitada, reservada o pendiente.");
            case "RETIRAR" -> exigir(bd.cambiar("""
                    UPDATE matricula SET estado='RETIRADA',hasta=? WHERE materia_id=? AND estudiante_id=? AND estado='APROBADA'
                    """, ahora.toLocalDate().plusDays(1), materia, alumno) == 1, "Solo se pueden retirar matrículas legalizadas.");
            default -> throw new IllegalArgumentException("Acción de matrícula desconocida.");
        }
        var actual=bd.uno("SELECT paralelo,estado FROM matricula WHERE materia_id=? AND estudiante_id=?",materia,alumno);
        if(actual!=null && !d[2].equals("HABILITAR") && !bd.uno("SELECT periodo_id FROM materia WHERE id=?",materia)[0].isBlank()){
            String estadoDetalle=switch(actual[1]){
                case "APROBADA" -> "CONFIRMADA";
                case "RECHAZADA" -> "RECHAZADA";
                case "RETIRADA" -> "ANULADA";
                default -> "PENDIENTE";
            };
            reservas.sincronizarDetalle(alumno,materia,actual[0].isBlank()?"A":actual[0],estadoDetalle);
            reservas.sincronizarEstadoPeriodo(alumno,materia,estadoDetalle);
        }
    }

    private void cancelarClase(Usuario u,String valor)throws SQLException{
        long clase=id(valor);
        var fila=bd.uno("SELECT m.docente_id FROM clase c JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id WHERE c.id=?",clase);
        exigir(fila!=null&&(u.admin()||Long.parseLong(fila[0])==u.id()),"La clase no pertenece a una materia asignada a tu cuenta.");
        exigir(bd.cambiar("UPDATE clase SET cancelada=TRUE WHERE id=? AND inicio>? AND cancelada=FALSE",clase,ahora)==1,
                "Solo se pueden cancelar clases futuras activas.");
    }

    private void registrar(String[] d) throws SQLException {
        String cedula = usuario(d[0], true);
        String codigo=d.length>4 && !d[4].isBlank()?texto(d[4],32,"el código estudiantil"):"";
        bd.cambiar("INSERT INTO cuenta(usuario,nombre,apellidos,clave,rol) VALUES(?,?,?,?,'ESTUDIANTE')",
                cedula, texto(d[1], 120, "los nombres completos"), texto(d[2], 120, "los apellidos completos"), Claves.crear(d[3]));
        long estudiante=Long.parseLong(bd.uno("SELECT LAST_INSERT_ID()")[0]);
        crearPerfil(estudiante, "ESTUDIANTE");
        if(!codigo.isBlank()) bd.cambiar("UPDATE perfil_estudiante SET codigo_estudiantil=? WHERE estudiante_id=?",codigo,estudiante);
        if(d.length>5&&!d[5].isBlank()) bd.cambiar("UPDATE perfil_estudiante SET correo=? WHERE estudiante_id=?",validarCorreo(d[5]),estudiante);
    }

    private List<String[]> horarios(long estudiante, long materia) throws SQLException {
        return bd.consultar("""
                SELECT c.id, p.nombre, c.tema, DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),
                    DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'), COALESCE(a.estado,IF(c.inicio<=?,'ABIERTA','PROGRAMADA')),c.paralelo,
                    COALESCE((
                        SELECT CONCAT(o.nombre,' · ',oc.tema,' (',DATE_FORMAT(oc.inicio,'%d/%m %H:%i'),'–',
                                      DATE_FORMAT(oc.fin,'%H:%i'),')')
                        FROM matricula oe JOIN materia o ON o.id=oe.materia_id
                        JOIN parcial op ON op.materia_id=o.id JOIN clase oc ON oc.parcial_id=op.id
                          AND oc.paralelo=COALESCE(oe.paralelo,'A')
                        WHERE oe.estudiante_id=e.estudiante_id AND oe.materia_id<>e.materia_id
                          AND oe.estado IN ('APROBADA','RETIRADA') AND oe.desde<=DATE(oc.inicio)
                          AND (oe.hasta IS NULL OR oe.hasta>DATE(oc.inicio))
                          AND NOT oc.cancelada AND oc.inicio<c.fin AND oc.fin>c.inicio
                        ORDER BY oc.inicio,oc.id LIMIT 1
                    ),'')
                FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN matricula e ON e.materia_id=p.materia_id AND e.estudiante_id=?
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                WHERE p.materia_id=? AND c.fin>? AND c.cancelada=FALSE AND
                """ + VIGENCIA + " ORDER BY c.inicio", ahora, estudiante, materia, ahora);
    }

    private List<String[]> marcar(long estudiante, String[] d) throws SQLException, IOException {
        long clase = id(d[0]);
        var fila = bd.uno("""
                SELECT DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'),c.tolerancia,p.materia_id
                FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN matricula e ON e.materia_id=p.materia_id AND e.estudiante_id=?
                WHERE c.id=? AND c.cancelada=FALSE AND
                """ + VIGENCIA + " FOR UPDATE", estudiante, clase);
        exigir(fila != null, "Necesitas una matrícula legalizada para esta clase.");
        var previa = bd.uno("SELECT estado FROM asistencia_registro WHERE clase_id=? AND estudiante_id=?", clase, estudiante);
        if (previa != null) return mensaje("Ya consta tu registro: " + previa[0] + ".");
        LocalDateTime inicio = LocalDateTime.parse(fila[0], HORARIO);
        LocalDateTime fin = LocalDateTime.parse(fila[1], HORARIO);
        exigir(!ahora.isBefore(inicio) && ahora.isBefore(fin), "La clase está fuera de su horario de registro.");
        byte[] plantilla = bd.obtenerPerfilFacial(estudiante);
        exigir(plantilla != null, "Registra primero tu rostro en la pestaña Face ID.");
        byte[] muestra;
        try { muestra = Base64.getDecoder().decode(d[1]); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("No se pudo validar el rostro capturado."); }
        exigir(coincideRostro(plantilla, muestra), "El rostro no coincide con el perfil Face ID registrado.");
        byte[] foto = fotoValida(d[2]);
        String estado = ahora.isBefore(inicio.plusMinutes(Integer.parseInt(fila[2]))) ? "ASISTENCIA" : "ATRASO";
        bd.cambiar("INSERT INTO asistencia_registro(clase_id,estudiante_id,fecha,estado,foto) VALUES(?,?,?,?,?)",
                clase, estudiante, ahora, estado, foto);
        return mensaje("Registro guardado: " + estado + ".");
    }

    static boolean coincideRostro(byte[] referencia, byte[] muestra) {
        if (referencia == null || muestra == null || referencia.length != 512 || muestra.length != 512) return false;
        java.nio.ByteBuffer a = java.nio.ByteBuffer.wrap(referencia).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(muestra).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        double producto = 0, normaA = 0, normaB = 0;
        for (int i = 0; i < 128; i++) {
            float x = a.getFloat(), y = b.getFloat();
            if (!Float.isFinite(x) || !Float.isFinite(y)) return false;
            producto += x * y;
            normaA += x * x;
            normaB += y * y;
        }
        return normaA > 0 && normaB > 0 && producto / Math.sqrt(normaA * normaB) >= 0.363;
    }

    private void guardarPerfilFacial(Usuario u, String valor) throws SQLException {
        rol(u, u.estudiante());
        exigir(bd.obtenerPerfilFacial(u.id()) == null, "Ya existe un perfil facial para esta cuenta.");
        byte[] descriptor;
        try { descriptor = Base64.getDecoder().decode(valor); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("La captura facial no es válida."); }
        bd.guardarPerfilFacial(u.id(), descriptor, "SFACE_2021DEC");
    }

    private static byte[] fotoValida(String imagen) {
        byte[] foto;
        try {
            foto = Base64.getDecoder().decode(imagen);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Captura una foto JPEG válida.", e);
        }
        exigir(foto.length > 3 && foto.length <= 1_000_000
                && foto[0] == (byte) 0xff && foto[1] == (byte) 0xd8, "Captura una foto JPEG válida de hasta 1 MB.");
        try (var entrada = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(foto))) {
            var lectores = ImageIO.getImageReaders(entrada);
            exigir(lectores.hasNext(), "La imagen no se pudo abrir.");
            var lector = lectores.next();
            try {
                lector.setInput(entrada);
                exigir(lector.getWidth(0) <= 1920 && lector.getHeight(0) <= 1080, "La imagen supera la resolución permitida.");
                lector.read(0);
            } finally {
                lector.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("La foto está incompleta o dañada.", e);
        }
        return foto;
    }

    private List<String[]> detalle(Usuario u, String parcial) throws SQLException {
        return bd.consultar(DETALLE, ahora, ahora, parcial(u, parcial));
    }

    private List<String[]> totalLegalizadas(Usuario u, long materia) throws SQLException {
        var fila = bd.uno("""
                SELECT COUNT(e.estudiante_id)
                FROM materia m LEFT JOIN matricula e ON e.materia_id=m.id
                    AND e.estado IN ('APROBADA','RETIRADA')
                    AND e.desde<=? AND (e.hasta IS NULL OR e.hasta>?)
                WHERE m.id=? AND (? OR m.docente_id=?)
                GROUP BY m.id
                """, ahora.toLocalDate(), ahora.toLocalDate(), materia, u.admin(), u.id());
        rol(u, fila != null);
        return Collections.singletonList(fila);
    }

    static List<String[]> resumen(List<String[]> detalle) {
        Map<String, int[]> conteos = new LinkedHashMap<>();
        Map<String, String[]> alumnos = new LinkedHashMap<>();
        for (String[] fila : detalle) {
            alumnos.putIfAbsent(fila[1], fila);
            int[] c = conteos.computeIfAbsent(fila[1], k -> new int[5]);
            switch (fila[ESTADO_DETALLE]) {
                case "A tiempo" -> c[0]++;
                case "Atraso" -> c[1]++;
                case "Falta sin justificar" -> c[2]++;
                case "Falta justificada" -> c[3]++;
                case "Programada", "En curso sin marca" -> c[4]++;
                default -> throw new IllegalStateException("Estado de asistencia desconocido");
            }
        }
        List<String[]> filas = new ArrayList<>();
        conteos.forEach((id, c) -> {
            String[] a = alumnos.get(id);
            int total = c[0] + c[1] + c[2] + c[3];
            filas.add(new String[]{a[2], a[3], "" + c[0], "" + c[1], "" + c[2], "" + c[3],
                "" + c[4], "" + total, total == 0 ? "" : String.format(Locale.ROOT, "%.2f", 100.0 * (c[0] + c[1]) / total)});
        });
        return filas;
    }

    private long validarClase(long parcial, String clase) throws SQLException {
        long id = id(clase);
        exigir(bd.uno("SELECT id FROM clase WHERE id=? AND parcial_id=? AND cancelada=FALSE", id, parcial) != null,
                "La clase no pertenece al parcial seleccionado.");
        return id;
    }

    private void justificar(Usuario u, String[] d) throws SQLException {
        exigir(u.rol().equals("DOCENTE"), "Solo el docente asignado puede justificar faltas.");
        long parcial = parcial(u, d[0]);
        long clase = validarClase(parcial, d[1]);
        long estudiante = id(d[2]);
        var fila = bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN matricula e ON e.materia_id=p.materia_id AND e.estudiante_id=?
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                WHERE c.id=? AND c.fin<=? AND a.clase_id IS NULL AND
                """ + VIGENCIA, estudiante, clase, ahora);
        exigir(fila != null, "Solo se puede justificar una falta de una clase finalizada.");
        bd.cambiar("""
                INSERT INTO justificacion(clase_id,estudiante_id,responsable_id,motivo,fecha) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE responsable_id=VALUES(responsable_id),motivo=VALUES(motivo),fecha=VALUES(fecha)
                """, clase, estudiante, u.id(), texto(d[3], 500, "el motivo"), ahora);
    }

    private List<String[]> clasesParaJustificar(Usuario u) throws SQLException {
        return bd.consultar("""
                SELECT c.id,m.codigo,m.nombre,c.tema,DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i')
                FROM matricula e JOIN materia m ON m.id=e.materia_id JOIN parcial p ON p.materia_id=m.id
                JOIN clase c ON c.parcial_id=p.id AND c.paralelo=COALESCE(e.paralelo,'A')
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                LEFT JOIN solicitud_justificacion sj ON sj.clase_id=c.id AND sj.estudiante_id=e.estudiante_id
                WHERE e.estudiante_id=? AND e.estado IN ('APROBADA','RETIRADA')
                  AND e.desde<=DATE(c.inicio) AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
                  AND c.fin<=? AND c.cancelada=FALSE AND a.clase_id IS NULL AND sj.id IS NULL
                ORDER BY c.fin DESC
                """, u.id(), ahora);
    }

    private List<String[]> resumenAsistencia(Usuario u, long materia) throws SQLException {
        var suma=bd.uno("""
                SELECT COUNT(*),COALESCE(SUM(a.estado='ASISTENCIA'),0),COALESCE(SUM(a.estado='ATRASO'),0),
                    COALESCE(SUM(a.clase_id IS NULL AND j.clase_id IS NOT NULL),0),
                    COALESCE(SUM(a.clase_id IS NULL AND j.clase_id IS NULL),0)
                FROM matricula e JOIN materia m ON m.id=e.materia_id JOIN parcial p ON p.materia_id=m.id
                JOIN clase c ON c.parcial_id=p.id AND c.paralelo=COALESCE(e.paralelo,'A')
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                LEFT JOIN justificacion j ON j.clase_id=c.id AND j.estudiante_id=e.estudiante_id
                WHERE e.estudiante_id=? AND e.materia_id=? AND e.estado IN ('APROBADA','RETIRADA')
                    AND e.desde<=DATE(c.inicio) AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
                    AND c.fin<=? AND c.cancelada=FALSE
                """, u.id(), materia, ahora);
        return Collections.singletonList(resumen(suma));
    }

    private List<String[]> resumenParcial(Usuario u,long parcial)throws SQLException{
        parcial(u,Long.toString(parcial));
        var suma=bd.uno("""
                SELECT COUNT(*),COALESCE(SUM(a.estado='ASISTENCIA'),0),COALESCE(SUM(a.estado='ATRASO'),0),
                    COALESCE(SUM(a.clase_id IS NULL AND j.clase_id IS NOT NULL),0),
                    COALESCE(SUM(a.clase_id IS NULL AND j.clase_id IS NULL),0)
                FROM clase c JOIN matricula e ON e.materia_id=(SELECT materia_id FROM parcial WHERE id=?)
                    AND c.paralelo=COALESCE(e.paralelo,'A')
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                LEFT JOIN justificacion j ON j.clase_id=c.id AND j.estudiante_id=e.estudiante_id
                WHERE c.parcial_id=? AND c.fin<=? AND c.cancelada=FALSE
                    AND e.estado IN ('APROBADA','RETIRADA') AND e.desde<=DATE(c.inicio)
                    AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
                """,parcial,parcial,ahora);
        return Collections.singletonList(resumen(suma));
    }

    String[] resumen(String[] suma)throws SQLException{
        int total=Integer.parseInt(suma[0]),asis=Integer.parseInt(suma[1]),atraso=Integer.parseInt(suma[2]);
        int justificadas=Integer.parseInt(suma[3]),faltas=Integer.parseInt(suma[4]);
        boolean atrasoCuenta=Boolean.parseBoolean(valorConfiguracion("asistencia_atraso_cuenta","true"));
        boolean excluyeJustificada=Boolean.parseBoolean(valorConfiguracion("asistencia_justificada_excluida","true"));
        int numerador=asis+(atrasoCuenta?atraso:0),denominador=total-(excluyeJustificada?justificadas:0);
        String porcentaje=denominador==0?"0.0":String.format(Locale.ROOT,"%.1f",100.0*numerador/denominador);
        return new String[]{Integer.toString(total),Integer.toString(asis),Integer.toString(atraso),Integer.toString(faltas),Integer.toString(justificadas),porcentaje};
    }

    private List<String[]> misJustificaciones(Usuario u) throws SQLException {
        return bd.consultar("""
                SELECT sj.id,m.codigo,m.nombre,c.tema,sj.motivo,sj.estado,
                    DATE_FORMAT(sj.solicitada_en,'%Y-%m-%d %H:%i'),COALESCE(sj.respuesta,''),IF(sj.documento IS NULL,'','Sí')
                FROM solicitud_justificacion sj JOIN clase c ON c.id=sj.clase_id
                JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id
                WHERE sj.estudiante_id=? ORDER BY sj.solicitada_en DESC
                """, u.id());
    }

    private List<String[]> solicitudesJustificacion(Usuario u) throws SQLException {
        String sql = """
                SELECT sj.id,CONCAT_WS(' ',cu.nombre,cu.apellidos),m.codigo,m.nombre,c.tema,
                    sj.motivo,DATE_FORMAT(sj.solicitada_en,'%Y-%m-%d %H:%i'),sj.estado,IF(sj.documento IS NULL,'','Sí')
                FROM solicitud_justificacion sj JOIN cuenta cu ON cu.id=sj.estudiante_id
                JOIN clase c ON c.id=sj.clase_id JOIN parcial p ON p.id=c.parcial_id
                JOIN materia m ON m.id=p.materia_id
                WHERE sj.estado='PENDIENTE' AND (? OR m.docente_id=?) ORDER BY sj.solicitada_en
                """;
        return bd.consultar(sql, u.admin(), u.id());
    }

    private void solicitarJustificacion(Usuario u, String[] d) throws SQLException {
        long clase = id(d[0]);
        String motivo = texto(d[1], 500, "el motivo");
        exigir(bd.uno("""
                SELECT c.id FROM matricula e JOIN materia m ON m.id=e.materia_id
                JOIN parcial p ON p.materia_id=m.id JOIN clase c ON c.parcial_id=p.id
                    AND c.paralelo=COALESCE(e.paralelo,'A')
                LEFT JOIN asistencia_efectiva a ON a.clase_id=c.id AND a.estudiante_id=e.estudiante_id
                WHERE e.estudiante_id=? AND c.id=? AND e.estado IN ('APROBADA','RETIRADA')
                    AND e.desde<=DATE(c.inicio) AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))
                    AND c.fin<=? AND c.cancelada=FALSE AND a.clase_id IS NULL
                """, u.id(), clase, ahora) != null, "Solo puedes solicitar justificar una falta de una clase finalizada.");
        exigir(bd.uno("SELECT id FROM solicitud_justificacion WHERE clase_id=? AND estudiante_id=?", clase, u.id()) == null,
                "Ya existe una solicitud para esta falta.");
        byte[] documento=documentoJustificacion(d.length>2?d[2]:"");
        bd.cambiar("INSERT INTO solicitud_justificacion(clase_id,estudiante_id,motivo,documento) VALUES(?,?,?,?)", clase, u.id(), motivo,documento);
    }

    private static byte[] documentoJustificacion(String base64){
        if(base64==null||base64.isBlank())return null;
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(base64);}catch(IllegalArgumentException e){throw new IllegalArgumentException("El documento adjunto no es válido.");}
        exigir(bytes.length>0&&bytes.length<=1_500_000,"El documento debe tener hasta 1.5 MB.");
        boolean pdf=bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-';
        boolean jpeg=bytes.length>=3&&(bytes[0]&255)==0xff&&(bytes[1]&255)==0xd8&&(bytes[2]&255)==0xff;
        boolean png=bytes.length>=8&&(bytes[0]&255)==0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G';
        exigir(pdf||jpeg||png,"Adjunta únicamente un PDF, JPEG o PNG válido.");
        return bytes;
    }

    private List<String[]> documentoJustificacion(Usuario u,String valor)throws SQLException{
        long solicitud=id(valor);
        var fila=bd.uno("""
                SELECT sj.clase_id,sj.estudiante_id,m.docente_id
                FROM solicitud_justificacion sj JOIN clase c ON c.id=sj.clase_id
                JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id WHERE sj.id=?
                """,solicitud);
        exigir(fila!=null&&(u.admin()||u.id()==Long.parseLong(fila[2])),"No tienes acceso a este documento.");
        byte[] binario=bd.binario("SELECT documento FROM solicitud_justificacion WHERE id=?",solicitud);
        exigir(binario!=null,"Esta solicitud no contiene documento adjunto.");
        String mime=binario.length>4&&binario[0]=='%'?"application/pdf":binario.length>3&&(binario[0]&255)==0x89?"image/png":"image/jpeg";
        return Collections.singletonList(new String[]{mime,Base64.getEncoder().encodeToString(binario)});
    }

    private void resolverJustificacion(Usuario u, String[] d) throws SQLException {
        long solicitud = id(d[0]);
        String estado = d[1];
        exigir(Set.of("APROBADA", "RECHAZADA").contains(estado), "Selecciona aprobar o rechazar la solicitud.");
        String respuesta = d[2].isBlank() ? null : texto(d[2], 500, "la respuesta");
        var fila = bd.uno("""
                SELECT sj.clase_id,sj.estudiante_id,sj.motivo,m.docente_id
                FROM solicitud_justificacion sj JOIN clase c ON c.id=sj.clase_id
                JOIN parcial p ON p.id=c.parcial_id JOIN materia m ON m.id=p.materia_id
                WHERE sj.id=? AND sj.estado='PENDIENTE'
                """, solicitud);
        exigir(fila != null && (u.admin() || Long.parseLong(fila[3]) == u.id()),
                "La solicitud no está pendiente o no pertenece a tus materias.");
        exigir(bd.cambiar("UPDATE solicitud_justificacion SET estado=?,revisada_por=?,revisada_en=?,respuesta=? WHERE id=? AND estado='PENDIENTE'",
                estado, u.id(), ahora, respuesta, solicitud) == 1, "Otra persona ya revisó esta solicitud.");
        if (estado.equals("APROBADA")) {
            bd.cambiar("INSERT INTO justificacion(clase_id,estudiante_id,responsable_id,motivo,fecha) VALUES(?,?,?,?,?)",
                    fila[0], fila[1], u.id(), fila[2], ahora);
        }
    }

    List<String[]> ejecutar(Usuario u, String accion, String[] d) throws SQLException, IOException {
        int campos = switch (accion) {
            case "cuentas", "materias", "misMaterias", "siguienteCodigoMateria", "miHorario", "finalizarMatricula", "semestres", "estadoFacial",
                    "clasesParaJustificar", "misJustificaciones", "solicitudesJustificacion", "miPerfil", "historialAcademico", "mallas", "ofertasDisponibles", "misPeriodosMatricula", "configuracionAcademica" -> 0;
            case "parciales", "clases", "matriculas", "horarios", "cancelar", "detalle", "totalLegalizadas",
                    "miListaParciales", "ofertaMatricula", "liberarCupo", "fotoReserva", "cupos", "paralelos", "horarioSemestre", "resumenAsistencia", "resumenParcial", "catalogo", "prerrequisitos", "prepararOferta", "horariosOferta" -> 1;
            case "marcar" -> d.length==4?4:3;
            case "fotoMatricula", "guardarCupo", "horarioSemanal", "guardarTemaClase" -> 2;
            case "guardarPerfilFacial" -> d.length==2?2:1;
            case "solicitarJustificacion" -> d.length == 3 ? 3 : 2;
            case "documentoJustificacion" -> 1;
            case "resolverJustificacion" -> 3;
            case "actualizarMiPerfil" -> 2;
            case "guardarPrerrequisito" -> 3;
            case "guardarResultadoAcademico", "excepcionMatricula" -> 4;
            case "auditoria" -> 0;
            case "parcialesPeriodo" -> 1;
            case "matricular", "foto", "configurarCupos", "configurarParalelo" -> 3;
            case "iniciarClaseAhora" -> 3;
            case "guardarParcial", "guardarParcialPeriodo", "justificar" -> 4;
            case "corregirAsistencia" -> 5;
            case "registrar" -> d.length == 6 ? 6 : d.length == 5 ? 5 : 4;
            case "guardarSemestre" -> d.length == 7 ? 7 : 4;
            case "guardarMalla" -> 6;
            case "guardarCatalogo" -> 7;
            case "guardarHorarioOferta" -> 8;
            case "guardarConfiguracionAcademica" -> 5;
            case "confirmarSeleccion" -> d.length;
            case "retirarFaceId", "bloquesConfirmados" -> 0;
            case "guardarMateria" -> d.length == 8 ? 8 : 6;
            case "guardarCuenta" -> d.length == 12 || d.length == 13 ? d.length : 6;
            case "guardarClase" -> d.length == 8 ? 8 : d.length == 7 ? 7 : 6;
            default -> throw new IllegalArgumentException("Operación desconocida.");
        };
        exigir(d.length == campos, "La operación tiene campos incompletos.");
        if (accion.equals("registrar")) {
            registrar(d);
            return mensaje("Cuenta creada. Ya puedes consultar las ofertas abiertas de tu malla.");
        }
        rol(u, u != null);
        if (accion.equals("semestres")) {
            var periodos = bd.consultar("SELECT id,nombre,desde,hasta,COALESCE(matricula_desde,''),COALESCE(matricula_hasta,''),estado FROM semestre_academico ORDER BY desde DESC");
            if (!periodos.isEmpty()) return periodos;
            var fechas = reservas.fechasPeriodoGeneral();
            if (fechas == null) return List.of();
            return Collections.singletonList(new String[]{"0", "Período de los parciales", fechas[0], fechas[1], ahora.toLocalDate().toString()});
        }
        if (u.estudiante()) return switch (accion) {
            case "estadoFacial" -> mensaje(bd.obtenerPerfilFacial(u.id()) == null ? "SIN_REGISTRAR" : "REGISTRADO");
            case "guardarPerfilFacial" -> {
                guardarPerfilFacial(u, d[0]);
                yield mensaje("Perfil facial Face ID registrado.");
            }
            case "miPerfil" -> bd.consultar("""
                    SELECT c.usuario,c.nombre,c.apellidos,COALESCE(p.codigo_estudiantil,''),COALESCE(p.correo,''),
                        COALESCE(p.telefono,''),COALESCE(p.nivel_referencia,''),p.estado,COALESCE(m.nombre,'Sin malla asignada')
                    FROM cuenta c JOIN perfil_estudiante p ON p.estudiante_id=c.id
                    LEFT JOIN malla_curricular m ON m.id=p.malla_id WHERE c.id=?
                    """,u.id());
            case "historialAcademico" -> bd.consultar("""
                    SELECT c.codigo,c.nombre,h.estado,COALESCE(h.calificacion,''),COALESCE(s.nombre,'Sin período'),
                        DATE_FORMAT(h.registrada_en,'%Y-%m-%d')
                    FROM historial_academico h JOIN materia_catalogo c ON c.id=h.materia_catalogo_id
                    LEFT JOIN semestre_academico s ON s.id=h.periodo_id WHERE h.estudiante_id=?
                    ORDER BY s.desde DESC,c.nivel,c.codigo
                    """,u.id());
            case "misPeriodosMatricula" -> bd.consultar("""
                    SELECT mp.id,s.nombre,mp.estado,DATE_FORMAT(mp.creada_en,'%Y-%m-%d %H:%i'),
                        COALESCE(DATE_FORMAT(mp.confirmada_en,'%Y-%m-%d %H:%i'),''),COUNT(d.id)
                    FROM matricula_periodo mp JOIN semestre_academico s ON s.id=mp.periodo_id
                    LEFT JOIN matricula_periodo_detalle d ON d.matricula_id=mp.id
                    WHERE mp.estudiante_id=? GROUP BY mp.id ORDER BY s.desde DESC
                    """,u.id());
            case "actualizarMiPerfil" -> {
                String correo=validarCorreo(d[0]);
                String telefono=d[1].isBlank()?null:texto(d[1],32,"el teléfono");
                bd.cambiar("UPDATE perfil_estudiante SET correo=?,telefono=? WHERE estudiante_id=?",correo,telefono,u.id());
                yield mensaje("Perfil actualizado.");
            }
            case "confirmarSeleccion" -> {
                exigir(d.length >= 4, "Selecciona una asignatura y acepta los términos de matrícula.");
                reservas.confirmarSeleccion(u.id(), id(d[0]), d);
                yield mensaje("Solicitud registrada. Se legalizará al cierre de la jornada.");
            }
            case "retirarFaceId" -> {
                bd.cambiar("DELETE FROM perfil_facial WHERE estudiante_id=?", u.id());
                yield mensaje("Consentimiento retirado y plantilla facial eliminada.");
            }
            case "bloquesConfirmados" -> reservas.bloquesConfirmados(u.id());
            case "ofertasDisponibles" -> reservas.ofertasDisponibles(u.id());
            case "misMaterias" -> bd.consultar("""
                    SELECT m.id,m.codigo,m.nombre,
                        IF(e.estado='PENDIENTE' AND e.paralelo IS NULL,'FALTA FOTO',e.estado),DATE_FORMAT(e.hasta,'%Y-%m-%d'),
                        IF(e.estado IN ('APROBADA','RETIRADA') AND e.desde<=? AND (e.hasta IS NULL OR e.hasta>?), 'SI','NO'),
                        e.paralelo,IF(e.foto_matricula IS NULL,'','Sí'),m.periodo_id,COALESCE(s.nombre,'Sin período')
                    FROM matricula e JOIN materia m ON m.id=e.materia_id LEFT JOIN semestre_academico s ON s.id=m.periodo_id
                    WHERE e.estudiante_id=? ORDER BY s.desde DESC,m.nombre,m.codigo
                    """, ahora.toLocalDate(), ahora.toLocalDate(), u.id());
            case "clasesParaJustificar" -> clasesParaJustificar(u);
            case "misJustificaciones" -> misJustificaciones(u);
            case "miListaParciales" -> bd.consultar("""
                    SELECT p.id,p.nombre FROM parcial p JOIN matricula e ON e.materia_id=p.materia_id
                    WHERE e.estudiante_id=? AND p.materia_id=? AND e.estado IN ('APROBADA','RETIRADA')
                    ORDER BY p.desde
                    """, u.id(), id(d[0]));
            case "resumenAsistencia" -> resumenAsistencia(u,id(d[0]));
            case "solicitarJustificacion" -> {
                solicitarJustificacion(u,d);
                yield mensaje("Solicitud enviada para revisión del docente.");
            }
            case "horarios" -> horarios(u.id(), id(d[0]));
            case "ofertaMatricula" -> reservas.oferta(u.id(), id(d[0]));
            case "horariosOferta" -> {
                reservas.validarOferta(u.id(),id(d[0]));
                yield reservas.horariosOferta(id(d[0]));
            }
            case "prepararOferta" -> {
                reservas.validarOferta(u.id(),id(d[0]));
                yield mensaje("Oferta disponible para reservar.");
            }
            case "cupos", "paralelos" -> {
                reservas.validarOferta(u.id(),id(d[0]));
                yield reservas.cupos(id(d[0]));
            }
            case "miHorario" -> reservas.horario(u.id());
            case "horarioSemanal" -> reservas.horarioSemanal(u.id(), Long.parseLong(d[0]), LocalDate.parse(d[1]));
            case "horarioSemestre" -> reservas.horarioSemestre(u.id(), Long.parseLong(d[0]));
            case "guardarCupo" -> {
                reservas.reservar(u.id(), id(d[0]), d[1]);
                yield mensaje("Cupo guardado para el paralelo " + d[1] + ".");
            }
            case "liberarCupo" -> {
                reservas.liberar(u.id(), id(d[0]));
                yield mensaje("Cupo liberado.");
            }
            case "fotoReserva" -> {
                byte[] foto = fotoValida(d[0]);
                reservas.foto(u.id(), foto);
                yield mensaje("Foto guardada. Revisa tu horario y finaliza la matrícula.");
            }
            case "finalizarMatricula" -> {
                reservas.finalizar(u.id());
                yield mensaje("Matrícula enviada a Administración para su legalización.");
            }
            case "marcar" -> marcar(u.id(), d);
            default -> throw new IllegalArgumentException("Tu cuenta no tiene permiso para esta acción.");
        };
        if (accion.equals("resumenParcial")) {
            exigir(u.rol().equals("DOCENTE") || u.admin(), "Tu cuenta no tiene permiso para esta acción.");
            return resumenParcial(u, id(d[0]));
        }
        if (accion.equals("configuracionAcademica")) {
            rol(u, u.admin());
            return bd.consultar("SELECT clave,valor FROM configuracion_academica ORDER BY clave");
        }
        switch (accion) {
            case "mallas": return bd.consultar("SELECT id,nombre,version,COALESCE(DATE_FORMAT(vigente_desde,'%Y-%m-%d'),''),COALESCE(DATE_FORMAT(vigente_hasta,'%Y-%m-%d'),''),estado,es_demostracion FROM malla_curricular ORDER BY estado DESC,nombre,version");
            case "catalogo": return bd.consultar("SELECT id,codigo,nombre,COALESCE(nivel,''),COALESCE(creditos,''),IF(activa,'ACTIVA','INACTIVA') FROM materia_catalogo WHERE malla_id=? ORDER BY nivel,codigo",id(d[0]));
            case "prerrequisitos": return bd.consultar("SELECT p.prerrequisito_id,c.codigo,c.nombre FROM materia_prerrequisito p JOIN materia_catalogo c ON c.id=p.prerrequisito_id WHERE p.materia_id=? ORDER BY c.codigo",id(d[0]));
            case "guardarMalla": rol(u, u.admin()); guardarMalla(d); return mensaje("Cambios guardados.");
            case "guardarCatalogo": rol(u, u.admin()); guardarCatalogo(d); return mensaje("Cambios guardados.");
            case "guardarPrerrequisito": rol(u, u.admin()); guardarPrerrequisito(d); return mensaje("Cambios guardados.");
            case "guardarHorarioOferta":
                rol(u, u.admin());
                reservas.guardarHorarioOferta(d);
                sincronizarClases(id(d[1]));
                return mensaje("Horario y clases actualizados.");
            case "guardarConfiguracionAcademica": rol(u, u.admin()); guardarConfiguracionAcademica(d); return mensaje("Cambios guardados.");
            case "materias":
                if (u.admin()) return bd.consultar("""
                        SELECT m.id,m.codigo,m.nombre,CONCAT_WS(' ',a.nombre,a.apellidos),a.id,m.nivel,
                            m.periodo_id,COALESCE(s.nombre,'Sin período'),m.catalogo_id,IF(m.activa,'ACTIVA','INACTIVA')
                        FROM materia m JOIN cuenta a ON a.id=m.docente_id
                        LEFT JOIN semestre_academico s ON s.id=m.periodo_id ORDER BY m.nombre,s.desde
                        """);
                return bd.consultar("""
                        SELECT m.id,m.codigo,m.nombre,CONCAT_WS(' ',a.nombre,a.apellidos),a.id,m.nivel,
                            m.periodo_id,COALESCE(s.nombre,'Sin período'),m.catalogo_id,IF(m.activa,'ACTIVA','INACTIVA')
                        FROM materia m JOIN cuenta a ON a.id=m.docente_id
                        LEFT JOIN semestre_academico s ON s.id=m.periodo_id
                        WHERE m.docente_id=? ORDER BY m.nombre,s.desde
                        """, u.id());
            case "parciales":
                return bd.consultar("""
                        SELECT p.id,p.nombre,p.desde,p.hasta FROM parcial p JOIN materia m ON m.id=p.materia_id
                        WHERE m.id=? AND (? OR m.docente_id=?) ORDER BY p.desde
                        """, id(d[0]), u.admin(), u.id());
            case "clases": return bd.consultar("""
                    SELECT c.id,c.tema,DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'),
                    c.tolerancia,IF(c.cancelada,'Cancelada','Activa'),c.paralelo,COALESCE(c.aula,''),
                    IF(c.horario_oferta_id IS NULL,'MANUAL','HORARIO'),IF(c.cancelada OR c.fin<=?,'','SI')
                    FROM clase c WHERE c.parcial_id=? ORDER BY c.inicio
                    """, ahora, parcial(u, d[0]));
            case "detalle": return detalle(u, d[0]);
            case "horariosOferta":
                exigir(bd.uno("SELECT id FROM materia WHERE id=? AND (? OR docente_id=?)",id(d[0]),u.admin(),u.id())!=null,"La oferta no está asignada a tu cuenta.");
                return reservas.horariosOferta(id(d[0]));
            case "cupos", "paralelos":
                exigir(bd.uno("SELECT id FROM materia WHERE id=? AND (? OR docente_id=?)",id(d[0]),u.admin(),u.id())!=null,"La oferta no está asignada a tu cuenta.");
                return reservas.cupos(id(d[0]));
            case "iniciarClaseAhora": return mensaje(iniciarClaseAhora(u,d));
            case "guardarTemaClase":
                guardarTemaClase(u,d);
                return mensaje("Tema guardado.");
            case "guardarClase":
                rol(u,u.admin());
                parcial(u,d[1]);guardarClase(d);return mensaje("Sesión de clase guardada.");
            case "cancelar":
                rol(u,u.admin());
                exigir(bd.uno("SELECT id FROM clase WHERE id=? AND horario_oferta_id IS NOT NULL", id(d[0])) == null,
                        "Las clases del horario se ajustan desde Ofertas académicas.");
                cancelarClase(u,d[0]);return mensaje("Clase cancelada.");
            case "solicitudesJustificacion": return solicitudesJustificacion(u);
            case "documentoJustificacion": return documentoJustificacion(u,d[0]);
            case "resolverJustificacion":
                resolverJustificacion(u,d);
                return mensaje("La solicitud fue revisada.");
            case "totalLegalizadas": return totalLegalizadas(u, id(d[0]));
            case "matriculas":
                if (u.admin()) break;
                return bd.consultar("""
                        SELECT s.id,s.usuario,CONCAT_WS(' ',s.nombre,s.apellidos),e.estado,'',e.desde,e.hasta,e.paralelo
                        FROM matricula e JOIN cuenta s ON s.id=e.estudiante_id JOIN materia m ON m.id=e.materia_id
                        WHERE m.id=? AND m.docente_id=? AND e.estado='APROBADA' ORDER BY e.paralelo,s.apellidos,s.nombre
                        """, id(d[0]), u.id());
            case "corregirAsistencia": {
                long clase = validarClase(parcial(u, d[0]), d[1]);
                long estudiante = id(d[2]);
                exigir(Set.of("ASISTENCIA","ATRASO","FALTA").contains(d[3]),"Elige un estado de asistencia.");
                String motivo = texto(d[4],500,"el motivo de la corrección");
                exigir(bd.uno("""
                        SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                        JOIN matricula e ON e.materia_id=p.materia_id AND e.estudiante_id=?
                        WHERE c.id=? AND c.fin<=? AND NOT c.cancelada AND
                        """ + VIGENCIA + " FOR UPDATE", estudiante,clase,ahora)!=null,
                        "La corrección requiere una clase finalizada y una matrícula vigente.");
                bd.cambiar("""
                        INSERT INTO correccion_asistencia(clase_id,estudiante_id,responsable_id,estado,motivo,fecha)
                        VALUES(?,?,?,?,?,?)
                        """,clase,estudiante,u.id(),d[3],motivo,ahora);
                return mensaje("Corrección de asistencia registrada.");
            }
            case "justificar":
                justificar(u, d);
                return mensaje("Justificación guardada.");
            case "foto":
                byte[] foto = bd.foto(validarClase(parcial(u, d[0]), d[1]), id(d[2]));
                exigir(foto != null, "Este registro no tiene fotografía.");
                return mensaje(Base64.getEncoder().encodeToString(foto));
            default:
                rol(u, u.admin());
        }
        switch (accion) {
            case "parcialesPeriodo":
                return bd.consultar("SELECT nombre,desde,hasta FROM parcial_periodo WHERE periodo_id=? ORDER BY desde", id(d[0]));
            case "guardarParcialPeriodo":
                guardarParcialPeriodo(id(d[0]), d[1], LocalDate.parse(d[2]), LocalDate.parse(d[3]));
                return mensaje("Parcial aplicado a las ofertas del período.");
            case "auditoria": return bd.consultar("""
                    SELECT a.creado_en,COALESCE(c.usuario,''),a.accion,COALESCE(a.referencia,'')
                    FROM auditoria_evento a LEFT JOIN cuenta c ON c.id=a.actor_id
                    ORDER BY a.id DESC LIMIT 100
                    """);
            case "excepcionMatricula": {
                long periodo = id(d[0]);
                long alumno = id(d[1]);
                reservas.bloquearAlumno(alumno);
                if (d[2].isBlank()) {
                    bd.cambiar("DELETE FROM excepcion_matricula_plazo WHERE estudiante_id=? AND periodo_id=?", alumno, periodo);
                    return mensaje("Excepción retirada.");
                }
                LocalDate hasta = LocalDate.parse(d[2]);
                String motivo = texto(d[3], 500, "el motivo de la autorización");
                exigir(bd.uno("""
                        SELECT id FROM semestre_academico WHERE id=?
                        AND estado IN ('MATRICULA_ABIERTA','MATRICULA_CERRADA','EN_CURSO') AND hasta>=? FOR UPDATE
                        """, periodo, hasta) != null && !hasta.isBefore(ahora.toLocalDate()),
                        "Selecciona una fecha vigente dentro de un período habilitado para excepciones.");
                bd.cambiar("""
                        INSERT INTO excepcion_matricula_plazo(estudiante_id,periodo_id,autorizada_por,hasta,motivo)
                        VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE autorizada_por=VALUES(autorizada_por),
                            hasta=VALUES(hasta),motivo=VALUES(motivo)
                        """, alumno,periodo,u.id(),hasta,motivo);
                return mensaje("Excepción de plazo registrada.");
            }
            case "guardarResultadoAcademico": {
                long alumno = id(d[0]);
                long oferta = id(d[1]);
                exigir(Set.of("APROBADA","REPROBADA","RETIRADA","HOMOLOGADA").contains(d[2]),
                        "Selecciona un resultado académico válido.");
                validarDecimalConfig(d[3],"0","100","La calificación debe quedar entre 0 y 100.");
                reservas.bloquearAlumno(alumno);
                var datos = bd.uno("""
                        SELECT m.catalogo_id,m.periodo_id FROM materia m JOIN perfil_estudiante pe ON pe.estudiante_id=?
                        JOIN materia_catalogo mc ON mc.id=m.catalogo_id AND mc.malla_id=pe.malla_id
                        WHERE m.id=? AND m.periodo_id IS NOT NULL
                        """, alumno, oferta);
                exigir(datos != null, "Selecciona una oferta de la malla del estudiante.");
                exigir(d[2].equals("HOMOLOGADA") || bd.uno("""
                        SELECT 1 FROM matricula WHERE materia_id=? AND estudiante_id=?
                        AND estado IN ('APROBADA','RETIRADA')
                        """, oferta, alumno) != null, "El estudiante debe tener una matrícula legalizada o retirada.");
                bd.cambiar("""
                        INSERT INTO historial_academico(estudiante_id,materia_catalogo_id,periodo_id,materia_oferta_id,estado,calificacion)
                        VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE materia_oferta_id=VALUES(materia_oferta_id),
                            estado=VALUES(estado),calificacion=VALUES(calificacion),registrada_en=CURRENT_TIMESTAMP
                        """, alumno, id(datos[0]), id(datos[1]), oferta, d[2],
                        d[3].isBlank() ? null : new java.math.BigDecimal(d[3]));
                return mensaje("Resultado académico registrado.");
            }
            case "cuentas": return bd.consultar("""
                    SELECT c.id,c.rol,c.usuario,CONCAT_WS(' ',c.nombre,c.apellidos),c.nombre,c.apellidos,
                        COALESCE(p.codigo_estudiantil,''),COALESCE(p.correo,d.correo,''),COALESCE(p.telefono,d.telefono,''),
                        COALESCE(p.malla_id,''),COALESCE(p.nivel_referencia,''),
                        CASE WHEN c.rol='ESTUDIANTE' THEN p.estado ELSE IF(d.activo,'ACTIVO','INACTIVO') END,
                        COALESCE(c.usuario_institucional,'')
                    FROM cuenta c LEFT JOIN perfil_estudiante p ON p.estudiante_id=c.id
                    LEFT JOIN perfil_docente d ON d.docente_id=c.id WHERE c.rol<>'ADMIN'
                    ORDER BY c.rol,c.nombre,c.apellidos
                    """);
            case "siguienteCodigoMateria": return mensaje(siguienteCodigoMateria());
            case "matriculas": return bd.consultar("""
                    SELECT s.id,s.usuario,CONCAT_WS(' ',s.nombre,s.apellidos),
                        IF(e.estado='PENDIENTE' AND e.paralelo IS NULL,'FALTA FOTO',e.estado),
                        IF(e.foto_matricula IS NULL,'','Sí'),e.desde,e.hasta,e.paralelo
                    FROM matricula e JOIN cuenta s ON s.id=e.estudiante_id WHERE e.materia_id=? ORDER BY s.nombre,s.apellidos
                    """, id(d[0]));
            case "fotoMatricula":
                byte[] evidencia = bd.fotoMatricula(id(d[0]), id(d[1]));
                exigir(evidencia != null, "Esta matrícula no tiene una foto guardada.");
                return mensaje(Base64.getEncoder().encodeToString(evidencia));
            case "guardarCuenta": guardarCuenta(d); break;
            case "guardarMateria": guardarMateria(d); break;
            case "guardarParcial": guardarParcial(d); break;
            case "guardarSemestre": guardarSemestre(d); break;
            case "guardarClase": guardarClase(d); break;
            case "cupos", "paralelos": return reservas.cupos(id(d[0]));
            case "configurarParalelo": reservas.configurarParalelo(id(d[0]),d[1],Integer.parseInt(d[2])); break;
            case "configurarCupos": reservas.configurar(id(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2])); break;
            case "matricular": matricular(d); break;
            default: throw new IllegalArgumentException("Operación desconocida.");
        }
        return mensaje("Cambios guardados.");
    }
}
