package ec.asistencia;

import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.Locale;

import static ec.asistencia.Academico.exigir;

final class Reservas {
    static final String TERMINOS_MATRICULA = "matricula-v1";
    private static final String OCUPA_CUPO = """
            (estado IN ('RESERVADA','APROBADA') OR (estado='PENDIENTE' AND paralelo IS NOT NULL)
                OR (estado='RETIRADA' AND hasta>?))
            """;
    private static final String CLASES_DEL_ALUMNO = """
            FROM matricula e JOIN materia m ON m.id=e.materia_id JOIN parcial p ON p.materia_id=m.id
            JOIN clase c ON c.parcial_id=p.id AND c.paralelo=COALESCE(e.paralelo,'A')
            WHERE e.estudiante_id=? AND c.cancelada=FALSE AND
            """;
    private static final String HORARIO = """
            SELECT c.id,CONCAT(m.codigo,' · ',m.nombre),c.paralelo,p.nombre,c.tema,
                DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'),e.estado,m.id
            """ + CLASES_DEL_ALUMNO;
    private static final String MATRICULA_EN_CLASE = """
            (e.estado='RESERVADA' OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL)
             OR (e.estado IN ('APROBADA','RETIRADA') AND e.desde<=DATE(c.inicio)
                 AND (e.hasta IS NULL OR e.hasta>DATE(c.inicio))))
            """;
    private static final String JORNADA_DEL_PARALELO = """
            SELECT IF(HOUR(j.inicio)<13,'MANANA','TARDE')
            FROM clase j JOIN parcial jp ON jp.id=j.parcial_id
            WHERE j.inicio>=? AND j.inicio<? AND j.cancelada=FALSE AND WEEKDAY(j.inicio)<5
                AND DATE(j.inicio)=DATE(j.fin)
                AND ((TIME(j.inicio)>='07:00:00' AND TIME(j.fin)<='13:00:00')
                    OR (TIME(j.inicio)>='14:00:00' AND TIME(j.fin)<='20:00:00'))
            """;
    private final Base bd;
    private final LocalDateTime ahora;

    Reservas(Base bd, LocalDateTime ahora) {
        this.bd = bd;
        this.ahora = ahora;
    }

    static String paralelo(String valor) {
        String limpio = valor.strip().toUpperCase(Locale.ROOT);
        exigir(limpio.matches("[A-Z0-9][A-Z0-9_-]{0,11}"), "El paralelo debe tener entre 1 y 12 caracteres alfanuméricos.");
        return limpio;
    }

    private String[] matricula(long alumno, long materia) throws SQLException {
        var fila = bd.uno("""
                SELECT estado,paralelo
                FROM matricula WHERE estudiante_id=? AND materia_id=?
                """, alumno, materia);
        exigir(fila != null, "Administración debe habilitarte esta materia.");
        return fila;
    }

    private String[] limites(long materia) throws SQLException {
        var fila = bd.uno("SELECT cupos_a,cupos_b FROM materia WHERE id=? FOR UPDATE", materia);
        exigir(fila != null, "Selecciona una materia válida.");
        return fila;
    }

    private int ocupados(long materia, String paralelo) throws SQLException {
        return Integer.parseInt(bd.uno("""
                SELECT COUNT(*) FROM matricula WHERE materia_id=? AND COALESCE(paralelo,'A')=? AND
                """ + OCUPA_CUPO, materia, paralelo, ahora.toLocalDate())[0]);
    }

    List<String[]> cupos(long materia) throws SQLException {
        limites(materia);
        var paralelos = bd.consultar("SELECT codigo,cupo_maximo FROM materia_paralelo WHERE materia_id=? AND activo=TRUE ORDER BY codigo", materia);
        var resultado = new java.util.ArrayList<String[]>();
        for (String[] paralelo : paralelos) {
            resultado.add(new String[]{paralelo[0], paralelo[1], Integer.toString(ocupados(materia, paralelo[0]))});
        }
        return resultado;
    }

    List<String[]> ofertasDisponibles(long alumno) throws SQLException {
        var ofertas=bd.consultar("""
                SELECT m.id,m.codigo,m.nombre,CONCAT_WS(' ',d.nombre,d.apellidos),COALESCE(m.nivel,''),
                    s.id,s.nombre,m.catalogo_id
                FROM materia m JOIN semestre_academico s ON s.id=m.periodo_id
                JOIN cuenta d ON d.id=m.docente_id JOIN perfil_estudiante pe ON pe.estudiante_id=?
                JOIN materia_catalogo mc ON mc.id=m.catalogo_id AND mc.malla_id=pe.malla_id AND mc.activa=TRUE
                WHERE m.activa=TRUE AND (
                    (s.estado='MATRICULA_ABIERTA' AND (s.matricula_desde IS NULL OR s.matricula_desde<=?)
                        AND (s.matricula_hasta IS NULL OR s.matricula_hasta>=?))
                    OR (s.estado IN ('MATRICULA_ABIERTA','MATRICULA_CERRADA','EN_CURSO') AND EXISTS (
                        SELECT 1 FROM excepcion_matricula_plazo x
                        WHERE x.periodo_id=s.id AND x.estudiante_id=pe.estudiante_id AND x.hasta>=?
                    )))
                    AND NOT EXISTS(SELECT 1 FROM matricula e WHERE e.estudiante_id=? AND e.materia_id=m.id AND e.estado NOT IN ('HABILITADA','RESERVADA'))
                ORDER BY s.desde,m.nivel,m.codigo
                """,alumno,ahora.toLocalDate(),ahora.toLocalDate(),ahora.toLocalDate(),alumno);
        var resultado=new java.util.ArrayList<String[]>();
        for(String[] oferta:ofertas){
            try { Curriculo.validarReserva(bd,alumno,Long.parseLong(oferta[0]),ahora.toLocalDate()); resultado.add(oferta); }
            catch(IllegalArgumentException incumpleRegla) { /* no se oferta hasta que cumpla su malla */ }
        }
        return resultado;
    }

    List<String[]> horariosOferta(long materia)throws SQLException{
        return bd.consultar("""
                SELECT id,paralelo,dia_semana,TIME_FORMAT(hora_inicio,'%H:%i'),
                    TIME_FORMAT(hora_fin,'%H:%i'),COALESCE(aula,''),IF(activo,'ACTIVO','INACTIVO')
                FROM oferta_horario WHERE materia_id=? ORDER BY paralelo,dia_semana,hora_inicio
                """,materia);
    }

    void guardarHorarioOferta(String[] d)throws SQLException{
        long materia=Long.parseLong(d[1]);
        String paralelo=paralelo(d[2]);
        int dia=Integer.parseInt(d[3]);
        LocalTime inicio=LocalTime.parse(d[4]),fin=LocalTime.parse(d[5]);
        String aula=d[6].isBlank()?null:Academico.texto(d[6],80,"el aula");
        boolean activo=Boolean.parseBoolean(d[7]);
        exigir(bd.uno("SELECT materia_id FROM materia_paralelo WHERE materia_id=? AND codigo=? AND activo=TRUE",materia,paralelo)!=null,
                "Selecciona un paralelo activo de esta oferta.");
        exigir(dia>=1&&dia<=5&&fin.isAfter(inicio),"Usa un día laborable y una hora final posterior a la inicial.");
        Jornada jornada=Jornada.de(Jornada.minutos(inicio),Jornada.minutos(fin));
        exigir(jornada!=null,"El horario debe quedar entre 07:00 y 13:00 o entre 14:00 y 20:00.");
        long id=d[0].isBlank()?0:Long.parseLong(d[0]);
        bd.uno("SELECT id FROM semestre_academico WHERE id=(SELECT periodo_id FROM materia WHERE id=?) FOR UPDATE", materia);
        if(activo){
            exigir(bd.uno("""
                    SELECT h.id FROM oferta_horario h JOIN materia otra ON otra.id=h.materia_id
                    JOIN materia actual ON actual.id=?
                    WHERE h.id<>? AND h.activo=TRUE AND h.dia_semana=?
                      AND otra.periodo_id <=> actual.periodo_id AND otra.docente_id=actual.docente_id AND h.hora_inicio<? AND h.hora_fin>?
                    """,materia,id,dia,fin,inicio)==null,"El docente ya tiene otro horario en ese bloque.");
            if(aula!=null)exigir(bd.uno("SELECT h.id FROM oferta_horario h JOIN materia otra ON otra.id=h.materia_id JOIN materia actual ON actual.id=? WHERE h.id<>? AND otra.periodo_id <=> actual.periodo_id AND h.activo=TRUE AND h.dia_semana=? AND UPPER(h.aula)=UPPER(?) AND h.hora_inicio<? AND h.hora_fin>?",
                    materia,id,dia,aula,fin,inicio)==null,"El aula ya está ocupada en ese horario.");
            var cruce = bd.uno("""
                    SELECT otra.codigo,otra.nombre,h.paralelo
                    FROM matricula e JOIN matricula o ON o.estudiante_id=e.estudiante_id AND o.materia_id<>e.materia_id
                    JOIN materia actual ON actual.id=e.materia_id
                    JOIN materia otra ON otra.id=o.materia_id AND otra.periodo_id=actual.periodo_id
                    JOIN oferta_horario h ON h.materia_id=otra.id AND h.paralelo=COALESCE(o.paralelo,'A')
                    WHERE e.materia_id=? AND COALESCE(e.paralelo,'A')=?
                      AND (e.estado IN ('APROBADA','RESERVADA') OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL))
                      AND (o.estado IN ('APROBADA','RESERVADA') OR (o.estado='PENDIENTE' AND o.paralelo IS NOT NULL))
                      AND h.activo AND h.dia_semana=? AND h.hora_inicio<? AND h.hora_fin>? LIMIT 1
                    """, materia, paralelo, dia, fin, inicio);
            exigir(cruce == null, cruce == null ? "" : "El horario se cruza para estudiantes inscritos en "
                    +cruce[0]+" · "+cruce[1]+", paralelo "+cruce[2]+".");
        }
        if(id==0)bd.cambiar("INSERT INTO oferta_horario(materia_id,paralelo,dia_semana,hora_inicio,hora_fin,aula,activo) VALUES(?,?,?,?,?,?,?)",
                materia,paralelo,dia,inicio,fin,aula,activo);
        else exigir(bd.cambiar("UPDATE oferta_horario SET paralelo=?,dia_semana=?,hora_inicio=?,hora_fin=?,aula=?,activo=? WHERE id=? AND materia_id=?",
                paralelo,dia,inicio,fin,aula,activo,id,materia)==1,"El horario ya no existe.");
    }

    void validarOferta(long alumno, long materia) throws SQLException {
        Curriculo.validarReserva(bd, alumno, materia, ahora.toLocalDate());
    }

    private void prepararOferta(long alumno,long materia) throws SQLException {
        validarOferta(alumno, materia);
        var fila=bd.uno("SELECT estado FROM matricula WHERE estudiante_id=? AND materia_id=?",alumno,materia);
        if(fila==null){
            bd.cambiar("INSERT INTO matricula(materia_id,estudiante_id,estado) VALUES(?,?,'HABILITADA')",materia,alumno);
        } else {
            exigir(!fila[0].equals("RECHAZADA"),"Administración debe volver a habilitar la solicitud rechazada.");
        }
    }

    private long idPeriodo(long materia) throws SQLException {
        var fila=bd.uno("SELECT periodo_id FROM materia WHERE id=?",materia);
        exigir(fila!=null&&fila[0]!=null&&!fila[0].isBlank(),"La oferta no tiene período académico asignado.");
        return Long.parseLong(fila[0]);
    }

    private long prepararMatriculaPeriodo(long alumno,long materia) throws SQLException {
        long periodo=idPeriodo(materia);
        bd.cambiar("INSERT INTO matricula_periodo(estudiante_id,periodo_id,estado) VALUES(?,?,'BORRADOR') ON DUPLICATE KEY UPDATE estado=IF(estado IN ('RECHAZADA','ANULADA'),'BORRADOR',estado)",alumno,periodo);
        return Long.parseLong(bd.uno("SELECT id FROM matricula_periodo WHERE estudiante_id=? AND periodo_id=?",alumno,periodo)[0]);
    }

    void sincronizarDetalle(long alumno,long materia,String paralelo,String estado) throws SQLException {
        long periodoMatricula=prepararMatriculaPeriodo(alumno,materia);
        bd.cambiar("INSERT INTO matricula_periodo_detalle(matricula_id,materia_id,paralelo,estado) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE legalizar_desde=IF(VALUES(estado)='PENDIENTE',NULL,legalizar_desde),paralelo=VALUES(paralelo),estado=VALUES(estado)",
                periodoMatricula,materia,paralelo,estado);
    }

    void sincronizarEstadoPeriodo(long alumno,long materia,String estado) throws SQLException {
        long periodo=idPeriodo(materia);
        var cabecera=bd.uno("SELECT id FROM matricula_periodo WHERE estudiante_id=? AND periodo_id=?",alumno,periodo);
        if(cabecera==null)return;
        bd.cambiar("UPDATE matricula_periodo_detalle SET estado=? WHERE matricula_id=? AND materia_id=?",estado,cabecera[0],materia);
        bd.cambiar("""
                UPDATE matricula_periodo mp SET estado=CASE
                  WHEN EXISTS(SELECT 1 FROM matricula_periodo_detalle d WHERE d.matricula_id=mp.id AND d.estado='PENDIENTE') THEN 'PENDIENTE'
                  WHEN EXISTS(SELECT 1 FROM matricula_periodo_detalle d WHERE d.matricula_id=mp.id AND d.estado='CONFIRMADA') THEN 'CONFIRMADA'
                  WHEN NOT EXISTS(SELECT 1 FROM matricula_periodo_detalle d WHERE d.matricula_id=mp.id AND d.estado NOT IN ('RECHAZADA')) THEN 'RECHAZADA'
                  WHEN NOT EXISTS(SELECT 1 FROM matricula_periodo_detalle d WHERE d.matricula_id=mp.id AND d.estado NOT IN ('ANULADA')) THEN 'ANULADA'
                  ELSE 'PENDIENTE' END,
                  confirmada_en=IF(estado='CONFIRMADA',COALESCE(confirmada_en,CURRENT_TIMESTAMP),NULL)
                WHERE mp.id=?
                """,cabecera[0]);
    }

    void configurar(long materia, int a, int b) throws SQLException {
        configurarParalelo(materia,"A",a);
        configurarParalelo(materia,"B",b);
        bd.cambiar("UPDATE materia SET cupos_a=?,cupos_b=? WHERE id=?", a, b, materia);
    }

    void configurarParalelo(long materia, String codigo, int cupo) throws SQLException {
        String grupo = paralelo(codigo);
        exigir(cupo >= 0 && cupo <= 500, "Ingresa entre 0 y 500 cupos.");
        limites(materia);
        exigir(cupo >= ocupados(materia, grupo), "El cupo no puede ser menor que las reservas y matrículas vigentes.");
        bd.cambiar("INSERT INTO materia_paralelo(materia_id,codigo,cupo_maximo,activo) VALUES(?,?,?,TRUE) ON DUPLICATE KEY UPDATE cupo_maximo=VALUES(cupo_maximo),activo=TRUE",
                materia, grupo, cupo);
    }

    List<String[]> oferta(long alumno, long materia) throws SQLException {
        Curriculo.validarReserva(bd,alumno,materia,ahora.toLocalDate());
        return bd.consultar("""
                SELECT c.id,c.paralelo,p.nombre,c.tema,DATE_FORMAT(c.inicio,'%Y-%m-%d %H:%i'),
                    DATE_FORMAT(c.fin,'%Y-%m-%d %H:%i'),COALESCE((SELECT GREATEST(0,mp.cupo_maximo-(
                        SELECT COUNT(*) FROM matricula e WHERE e.materia_id=mp.materia_id
                          AND COALESCE(e.paralelo,'A')=mp.codigo
                          AND (e.estado IN ('RESERVADA','APROBADA')
                            OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL)
                            OR (e.estado='RETIRADA' AND e.hasta>?))))
                        FROM materia_paralelo mp WHERE mp.materia_id=p.materia_id
                          AND mp.codigo=c.paralelo AND mp.activo=TRUE),0)
                FROM clase c JOIN parcial p ON p.id=c.parcial_id
                WHERE p.materia_id=? AND c.cancelada=FALSE AND c.inicio>?
                  AND EXISTS(SELECT 1 FROM materia_paralelo mp WHERE mp.materia_id=p.materia_id
                    AND mp.codigo=c.paralelo AND mp.activo=TRUE)
                ORDER BY c.paralelo,c.inicio
                """, ahora.toLocalDate(), materia, ahora);
    }

    private int disponibles(String[] fila) {
        return Math.max(0, Integer.parseInt(fila[1]) - Integer.parseInt(fila[2]));
    }

    private boolean puedeReservar(String[] fila) {
        return fila[0].equals("HABILITADA") || fila[0].equals("RESERVADA")
                || (fila[0].equals("PENDIENTE") && fila[1].isBlank());
    }

    void reservar(long alumno, long materia, String grupo) throws SQLException {
        grupo = paralelo(grupo);
        bloquearAlumno(alumno);
        limites(materia);
        prepararOferta(alumno,materia);
        var fila = matricula(alumno, materia);
        exigir(puedeReservar(fila), "Esta matrícula ya no está disponible para reservar.");
        Curriculo.validarReserva(bd,alumno,materia,ahora.toLocalDate());
        if (fila[0].equals("RESERVADA") && fila[1].equals(grupo)) return;
        exigir(bd.uno("""
                SELECT id FROM oferta_horario WHERE materia_id=? AND paralelo=? AND activo=TRUE
                UNION ALL SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                WHERE p.materia_id=? AND c.paralelo=? AND c.cancelada=FALSE AND c.inicio>? LIMIT 1
                """, materia, grupo, materia, grupo, ahora) != null, "El paralelo aún no tiene horario disponible.");
        var cupo = bd.uno("SELECT cupo_maximo FROM materia_paralelo WHERE materia_id=? AND codigo=? AND activo=TRUE FOR UPDATE", materia, grupo);
        exigir(cupo != null, "Ese paralelo no está habilitado para esta oferta.");
        int capacidad = Integer.parseInt(cupo[0]);
        exigir(capacidad > ocupados(materia, grupo), "El paralelo ya no tiene cupos disponibles.");
        exigir(bd.uno("""
                SELECT c.id FROM clase c JOIN parcial p ON p.id=c.parcial_id
                JOIN matricula e ON e.estudiante_id=? AND e.materia_id<>p.materia_id
                JOIN parcial otro ON otro.materia_id=e.materia_id
                JOIN clase o ON o.parcial_id=otro.id AND o.paralelo=COALESCE(e.paralelo,'A')
                WHERE p.materia_id=? AND c.paralelo=? AND c.cancelada=FALSE AND o.cancelada=FALSE
                    AND c.inicio>? AND o.fin>? AND c.inicio<o.fin AND o.inicio<c.fin
                    AND (e.estado IN ('RESERVADA','APROBADA') OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL) OR
                        (e.estado='RETIRADA' AND e.hasta>DATE(o.inicio)))
                LIMIT 1
                """, alumno, materia, grupo, ahora, ahora) == null, "Ese paralelo se cruza con otra materia de tu horario.");
        exigir(bd.uno("""
                SELECT nueva.id FROM oferta_horario nueva
                JOIN materia actual ON actual.id=nueva.materia_id
                JOIN oferta_horario existente ON existente.dia_semana=nueva.dia_semana
                    AND existente.hora_inicio<nueva.hora_fin AND existente.hora_fin>nueva.hora_inicio
                    AND existente.activo=TRUE
                JOIN materia otra ON otra.id=existente.materia_id AND otra.periodo_id=actual.periodo_id
                JOIN matricula e ON e.materia_id=otra.id AND e.estudiante_id=?
                WHERE nueva.materia_id=? AND nueva.paralelo=? AND nueva.activo=TRUE
                    AND otra.id<>actual.id AND existente.paralelo=COALESCE(e.paralelo,'A')
                    AND (e.estado IN ('RESERVADA','APROBADA')
                        OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL)
                        OR (e.estado='RETIRADA' AND e.hasta>?)
                    ) LIMIT 1
                """, alumno, materia, grupo, ahora.toLocalDate()) == null,
                "El horario semanal de este paralelo se cruza con otra materia de tu matrícula.");
        exigir(bd.cambiar("""
                UPDATE matricula SET estado='RESERVADA',paralelo=?,foto_matricula=NULL
                WHERE estudiante_id=? AND materia_id=? AND (estado IN ('HABILITADA','RESERVADA') OR
                    (estado='PENDIENTE' AND paralelo IS NULL))
                """, grupo, alumno, materia) == 1, "No se pudo guardar el cupo. Actualiza tus matrículas.");
        sincronizarDetalle(alumno,materia,grupo,"PENDIENTE");
    }

    void bloquearAlumno(long alumno) throws SQLException {
        exigir(bd.uno("SELECT id FROM cuenta WHERE id=? AND rol='ESTUDIANTE' FOR UPDATE", alumno) != null,
                "Selecciona un estudiante válido.");
    }

    void confirmarSeleccion(long alumno, long periodo, String[] seleccion) throws SQLException {
        exigir(seleccion.length >= 4 && seleccion.length <= 82 && seleccion.length % 2 == 0,
                "Selecciona entre 1 y 40 asignaturas.");
        exigir(TERMINOS_MATRICULA.equals(seleccion[1]), "Acepta los términos vigentes de matrícula.");
        bloquearAlumno(alumno);
        var ofertas = new java.util.TreeMap<Long, String>();
        for (int i = 2; i < seleccion.length; i += 2) {
            long materia = Long.parseLong(seleccion[i]);
            exigir(ofertas.putIfAbsent(materia, paralelo(seleccion[i + 1])) == null,
                    "La selección contiene una oferta duplicada.");
        }
        for (long materia : ofertas.keySet()) limites(materia);
        exigir(bd.uno("SELECT id FROM semestre_academico WHERE id=? FOR UPDATE", periodo) != null,
                "Selecciona un período válido.");
        LocalDateTime cierre = ahora.toLocalDate().plusDays(1).atStartOfDay();
        for (var oferta : ofertas.entrySet()) {
            long materia = oferta.getKey();
            exigir(idPeriodo(materia) == periodo, "Todas las asignaturas deben pertenecer al período seleccionado.");
            reservar(alumno, materia, oferta.getValue());
            bd.cambiar("""
                    UPDATE matricula SET estado='PENDIENTE',desde=NULL,hasta=NULL
                    WHERE estudiante_id=? AND materia_id=? AND estado='RESERVADA'
                    """, alumno, materia);
            bd.cambiar("""
                    UPDATE matricula_periodo_detalle d JOIN matricula_periodo mp ON mp.id=d.matricula_id
                    SET d.terminos_version=?,d.terminos_aceptados_en=?,d.legalizar_desde=?
                    WHERE mp.estudiante_id=? AND mp.periodo_id=? AND d.materia_id=?
                    """, TERMINOS_MATRICULA, ahora, cierre, alumno, periodo, materia);
            sincronizarEstadoPeriodo(alumno, materia, "PENDIENTE");
        }
    }

    void legalizarAlCierre(long alumno, long materia) throws SQLException {
        bloquearAlumno(alumno);
        limites(materia);
        int aprobadas = bd.cambiar("""
                UPDATE matricula e JOIN materia m ON m.id=e.materia_id
                JOIN matricula_periodo mp ON mp.estudiante_id=e.estudiante_id AND mp.periodo_id=m.periodo_id
                JOIN matricula_periodo_detalle d ON d.matricula_id=mp.id AND d.materia_id=e.materia_id
                JOIN perfil_estudiante p ON p.estudiante_id=e.estudiante_id
                SET e.estado='APROBADA',e.desde=DATE(d.legalizar_desde),e.hasta=NULL
                WHERE e.estudiante_id=? AND e.materia_id=? AND e.estado='PENDIENTE'
                  AND e.paralelo=d.paralelo AND d.estado='PENDIENTE' AND p.estado='ACTIVO'
                  AND d.legalizar_desde<=? AND d.terminos_aceptados_en IS NOT NULL
                """, alumno, materia, ahora);
        if (aprobadas == 1) {
            sincronizarEstadoPeriodo(alumno, materia, "CONFIRMADA");
            bd.cambiar("INSERT INTO auditoria_evento(accion,entidad,referencia) VALUES('legalizarAlCierre','matricula',?)",
                    alumno + ":" + materia);
        }
    }

    List<String[]> bloquesConfirmados(long alumno) throws SQLException {
        return bd.consultar("""
                SELECT m.codigo,m.nombre,h.paralelo,h.dia_semana,TIME_FORMAT(h.hora_inicio,'%H:%i'),
                       TIME_FORMAT(h.hora_fin,'%H:%i'),h.aula,s.nombre
                FROM matricula e JOIN materia m ON m.id=e.materia_id
                JOIN semestre_academico s ON s.id=m.periodo_id
                JOIN oferta_horario h ON h.materia_id=m.id AND h.paralelo=e.paralelo AND h.activo
                WHERE e.estudiante_id=? AND e.estado='APROBADA'
                ORDER BY s.desde DESC,h.dia_semana,h.hora_inicio,m.codigo
                """, alumno);
    }

    void liberar(long alumno, long materia) throws SQLException {
        limites(materia);
        exigir(bd.cambiar("""
                UPDATE matricula SET estado='HABILITADA',paralelo=NULL,foto_matricula=NULL
                WHERE estudiante_id=? AND materia_id=? AND estado='RESERVADA'
                """, alumno, materia) == 1, "Solo se pueden liberar cupos aún reservados.");
        sincronizarEstadoPeriodo(alumno,materia,"ANULADA");
    }

    void foto(long alumno, byte[] foto) throws SQLException {
        exigir(bd.uno("SELECT materia_id FROM matricula WHERE estudiante_id=? AND estado='RESERVADA' LIMIT 1", alumno) != null,
                "Guarda al menos un cupo antes de tomar la foto.");
        bd.cambiar("UPDATE matricula SET foto_matricula=? WHERE estudiante_id=? AND estado='RESERVADA'", foto, alumno);
    }

    void finalizar(long alumno) throws SQLException {
        var fila = bd.uno("""
                SELECT COUNT(*),COALESCE(SUM(foto_matricula IS NULL OR paralelo IS NULL),0)
                FROM matricula WHERE estudiante_id=? AND estado='RESERVADA'
                """, alumno);
        exigir(!fila[0].equals("0"), "Primero guarda un cupo.");
        exigir(fila[1].equals("0"), "Toma la foto de matrícula antes de finalizar.");
        bd.cambiar("UPDATE matricula SET estado='PENDIENTE' WHERE estudiante_id=? AND estado='RESERVADA'", alumno);
        bd.cambiar("UPDATE matricula_periodo mp JOIN matricula_periodo_detalle d ON d.matricula_id=mp.id SET mp.estado='PENDIENTE' WHERE mp.estudiante_id=? AND d.estado='PENDIENTE'",alumno);
    }

    List<String[]> horario(long alumno) throws SQLException {
        return bd.consultar(HORARIO + """
                c.fin>? AND
                    (e.estado IN ('RESERVADA','APROBADA') OR (e.estado='PENDIENTE' AND e.paralelo IS NOT NULL)
                        OR (e.estado='RETIRADA' AND e.hasta>DATE(c.inicio)))
                ORDER BY c.inicio,m.codigo
                """, alumno, ahora);
    }

    List<String[]> horarioSemanal(long alumno, long semestre, LocalDate lunes) throws SQLException {
        exigir(lunes.getDayOfWeek() == DayOfWeek.MONDAY, "Selecciona una semana que empiece el lunes.");
        var fechas = fechasSemestre(semestre);
        LocalDate desde = LocalDate.parse(fechas[0]);
        LocalDate hasta = LocalDate.parse(fechas[1]).plusDays(1);
        if (lunes.isAfter(desde)) desde = lunes;
        LocalDate sabado = lunes.plusDays(5);
        if (sabado.isBefore(hasta)) hasta = sabado;
        if (!desde.isBefore(hasta)) return List.of();
        return bd.consultar(HORARIO + """
                c.inicio>=? AND c.inicio<? AND
                """ + MATRICULA_EN_CLASE + " ORDER BY c.inicio,m.codigo", alumno, desde.atStartOfDay(), hasta.atStartOfDay());
    }

    List<String[]> horarioSemestre(long alumno, long semestre) throws SQLException {
        var fechas = fechasSemestre(semestre);
        var inicio = LocalDate.parse(fechas[0]).atStartOfDay();
        var fin = LocalDate.parse(fechas[1]).plusDays(1).atStartOfDay();
        return bd.consultar("""
                SELECT DISTINCT WEEKDAY(c.inicio),DATE_FORMAT(c.inicio,'%H:%i'),
                    TIMESTAMPDIFF(MINUTE,c.inicio,LEAST(c.fin,?)),CONCAT(m.codigo,' · ',m.nombre),c.paralelo,m.nivel,
                    (
                """ + JORNADA_DEL_PARALELO + """
                AND jp.materia_id=m.id AND j.paralelo=c.paralelo ORDER BY j.id LIMIT 1
                )
                """ + CLASES_DEL_ALUMNO + """
                c.inicio>=? AND c.inicio<? AND WEEKDAY(c.inicio)<5 AND
                """ + MATRICULA_EN_CLASE + " ORDER BY 1,2,4,5,3", fin, inicio, fin, alumno, inicio, fin);
    }

    Jornada jornadaParalelo(long materia, String paralelo, LocalDate desde, LocalDate hasta, long excluirClase) throws SQLException {
        var fila = bd.uno(JORNADA_DEL_PARALELO + """
                AND jp.materia_id=? AND j.paralelo=? AND j.id<>? ORDER BY j.id LIMIT 1
                """, desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay(), materia, paralelo, excluirClase);
        return fila == null ? null : Jornada.valueOf(fila[0]);
    }

    String[] fechasPeriodoGeneral() throws SQLException {
        return bd.uno("""
                SELECT MIN(desde),MAX(hasta) FROM parcial
                WHERE NOT EXISTS (SELECT 1 FROM semestre_academico)
                HAVING COUNT(*)>0
                """);
    }

    private String[] fechasSemestre(long semestre) throws SQLException {
        var fechas = semestre == 0 ? fechasPeriodoGeneral()
                : bd.uno("SELECT desde,hasta FROM semestre_academico WHERE id=?", semestre);
        exigir(fechas != null, "Selecciona un semestre válido.");
        return fechas;
    }
}
