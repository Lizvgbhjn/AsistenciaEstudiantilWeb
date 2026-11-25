USE asistencia;

-- La confirmación y la aprobación académica son operaciones distintas.
INSERT IGNORE INTO configuracion_academica(clave,valor)
VALUES ('matricula_requiere_legalizacion','true');

CREATE INDEX IF NOT EXISTS idx_matricula_oferta_paralelo_estado
    ON matricula(materia_id,paralelo,estado);

CREATE INDEX IF NOT EXISTS idx_horario_dia_bloque
    ON oferta_horario(dia_semana,activo,hora_inicio,hora_fin);

-- Conserva la marcación biométrica original y la historia de correcciones.
CREATE TABLE IF NOT EXISTS correccion_asistencia (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clase_id BIGINT NOT NULL,
    estudiante_id BIGINT NOT NULL,
    responsable_id BIGINT NOT NULL,
    estado ENUM('ASISTENCIA','ATRASO','FALTA') NOT NULL,
    motivo VARCHAR(500) NOT NULL,
    fecha DATETIME NOT NULL,
    FOREIGN KEY (clase_id) REFERENCES clase(id),
    FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    FOREIGN KEY (responsable_id) REFERENCES cuenta(id),
    KEY idx_correccion_clase_estudiante (clase_id,estudiante_id,id)
) ENGINE=InnoDB;

CREATE OR REPLACE VIEW asistencia_efectiva AS
SELECT a.clase_id,a.estudiante_id,a.fecha,a.estado
FROM asistencia_registro a
WHERE NOT EXISTS (
    SELECT 1 FROM correccion_asistencia c
    WHERE c.clase_id=a.clase_id AND c.estudiante_id=a.estudiante_id
)
UNION ALL
SELECT c.clase_id,c.estudiante_id,c.fecha,c.estado
FROM correccion_asistencia c
WHERE c.estado IN ('ASISTENCIA','ATRASO') AND NOT EXISTS (
    SELECT 1 FROM correccion_asistencia otra
    WHERE otra.clase_id=c.clase_id AND otra.estudiante_id=c.estudiante_id AND otra.id>c.id
);

CREATE TABLE IF NOT EXISTS excepcion_matricula_plazo (
    estudiante_id BIGINT NOT NULL,
    periodo_id BIGINT NOT NULL,
    autorizada_por BIGINT NOT NULL,
    hasta DATE NOT NULL,
    motivo VARCHAR(500) NOT NULL,
    registrada_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (estudiante_id,periodo_id),
    FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    FOREIGN KEY (periodo_id) REFERENCES semestre_academico(id),
    FOREIGN KEY (autorizada_por) REFERENCES cuenta(id)
) ENGINE=InnoDB;
