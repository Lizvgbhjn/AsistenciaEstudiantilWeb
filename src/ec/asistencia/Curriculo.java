package ec.asistencia;

import java.sql.SQLException;
import java.time.LocalDate;

/** Validaciones curriculares aplicadas antes de reservar una oferta. */
final class Curriculo {
    private Curriculo() {}

    static void validarReserva(Base bd, long estudiante, long materia, LocalDate hoy) throws SQLException {
        var oferta = bd.uno("""
                SELECT m.catalogo_id,m.periodo_id,m.activa,s.estado,s.matricula_desde,s.matricula_hasta
                FROM materia m JOIN semestre_academico s ON s.id=m.periodo_id WHERE m.id=?
                """, materia);
        Academico.exigir(oferta != null && (oferta[2] == null || oferta[2].equals("1") || oferta[2].equalsIgnoreCase("true")),
                "La oferta seleccionada no está activa.");
        boolean plazoAbierto = "MATRICULA_ABIERTA".equals(oferta[3])
                && (oferta[4].isBlank() || !hoy.isBefore(LocalDate.parse(oferta[4])))
                && (oferta[5].isBlank() || !hoy.isAfter(LocalDate.parse(oferta[5])));
        if (!plazoAbierto) {
            boolean admiteExcepcion = java.util.Set.of("MATRICULA_ABIERTA","MATRICULA_CERRADA","EN_CURSO").contains(oferta[3]);
            Academico.exigir(admiteExcepcion && bd.uno("""
                    SELECT 1 FROM excepcion_matricula_plazo WHERE estudiante_id=? AND periodo_id=? AND hasta>=?
                    """, estudiante, Long.parseLong(oferta[1]), hoy) != null,
                    "El período está fuera del plazo de matrícula autorizado.");
        }
        Academico.exigir(!oferta[0].isBlank(), "La oferta debe estar vinculada a un catálogo curricular.");
        long catalogo = Long.parseLong(oferta[0]);
        var perfil = bd.uno("SELECT malla_id FROM perfil_estudiante WHERE estudiante_id=? AND estado='ACTIVO'", estudiante);
        var materiaCatalogo = bd.uno("SELECT c.malla_id,c.activa,c.creditos FROM materia_catalogo c JOIN malla_curricular m ON m.id=c.malla_id AND m.estado='ACTIVA' WHERE c.id=?", catalogo);
        Academico.exigir(materiaCatalogo != null && (materiaCatalogo[1].equals("1") || materiaCatalogo[1].equalsIgnoreCase("true")),
                "La materia ya no está activa en el catálogo.");
        Academico.exigir(perfil != null && perfil[0].equals(materiaCatalogo[0]),
                "La materia no pertenece a tu malla curricular.");
        Academico.exigir(bd.uno("""
                SELECT e.materia_id FROM matricula e JOIN materia m ON m.id=e.materia_id
                WHERE e.estudiante_id=? AND m.periodo_id=? AND m.catalogo_id=? AND m.id<>?
                  AND e.estado IN ('RESERVADA','PENDIENTE','APROBADA') AND e.paralelo IS NOT NULL
                LIMIT 1
                """, estudiante, oferta[1], catalogo, materia) == null,
                "Ya seleccionaste esta asignatura en el período.");
        var repetir = bd.uno("SELECT valor FROM configuracion_academica WHERE clave='repetir_materia_aprobada'");
        boolean permitirRepetir = repetir != null && Boolean.parseBoolean(repetir[0]);
        if (!permitirRepetir) {
            Academico.exigir(bd.uno("""
                    SELECT h.id FROM historial_academico h
                    WHERE h.estudiante_id=? AND h.materia_catalogo_id=? AND h.estado IN ('APROBADA','HOMOLOGADA') LIMIT 1
                    """, estudiante, catalogo) == null,
                    "Ya aprobaste esta materia; la normativa de repetición debe ser autorizada por Administración.");
        }
        var faltante = bd.uno("""
                SELECT prereq.nombre
                FROM materia_prerrequisito r JOIN materia_catalogo prereq ON prereq.id=r.prerrequisito_id
                WHERE r.materia_id=? AND NOT EXISTS (
                    SELECT 1 FROM historial_academico h
                    WHERE h.estudiante_id=? AND h.materia_catalogo_id=r.prerrequisito_id
                      AND h.estado IN ('APROBADA','HOMOLOGADA')
                ) LIMIT 1
                """, catalogo, estudiante);
        Academico.exigir(faltante == null, "Primero debes aprobar el prerrequisito: " + (faltante == null ? "" : faltante[0]));
        var maximo = bd.uno("SELECT valor FROM configuracion_academica WHERE clave='max_creditos_matricula'");
        if (maximo != null && !maximo[0].isBlank()) {
            double limite = Double.parseDouble(maximo[0]);
            Academico.exigir(Double.isFinite(limite) && limite >= 0,
                    "El límite de créditos configurado no es válido.");
            String[] creditosActuales = bd.uno("""
                    SELECT COALESCE(SUM(c.creditos),0)
                    FROM matricula e JOIN materia m ON m.id=e.materia_id
                    JOIN materia_catalogo c ON c.id=m.catalogo_id
                    WHERE e.estudiante_id=? AND m.periodo_id=? AND m.id<>?
                      AND e.estado IN ('RESERVADA','PENDIENTE','APROBADA') AND e.paralelo IS NOT NULL
                    """, estudiante, oferta[1], materia);
            double actuales = Double.parseDouble(creditosActuales[0]);
            double nuevos = materiaCatalogo[2].isBlank() ? 0 : Double.parseDouble(materiaCatalogo[2]);
            Academico.exigir(actuales + nuevos <= limite, "Superas el máximo de créditos configurado para este período.");
        }
    }
}
