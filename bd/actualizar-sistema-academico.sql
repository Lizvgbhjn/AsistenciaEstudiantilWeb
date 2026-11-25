USE asistencia;

-- Migration incrementale: conserva las tablas y los registros preexistentes.
CREATE TABLE IF NOT EXISTS malla_curricular (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(120) NOT NULL,
    version VARCHAR(40) NOT NULL,
    universidad VARCHAR(120) NOT NULL DEFAULT 'Universidad Técnica de Ambato',
    facultad VARCHAR(120) NOT NULL DEFAULT 'FISEI',
    carrera VARCHAR(120) NOT NULL DEFAULT 'Ingeniería de Software',
    vigente_desde DATE NULL,
    vigente_hasta DATE NULL,
    estado ENUM('ACTIVA','INACTIVA') NOT NULL DEFAULT 'ACTIVA',
    es_demostracion BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_malla_version (nombre,version)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS materia_catalogo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    malla_id BIGINT NOT NULL,
    codigo VARCHAR(24) NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    nivel TINYINT UNSIGNED NULL,
    creditos DECIMAL(5,2) NULL,
    horas_semanales SMALLINT UNSIGNED NULL,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    es_demostracion BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_catalogo_codigo_malla (malla_id,codigo),
    CONSTRAINT fk_catalogo_malla FOREIGN KEY (malla_id) REFERENCES malla_curricular(id),
    CHECK (nivel IS NULL OR nivel BETWEEN 1 AND 16),
    CHECK (creditos IS NULL OR creditos >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS materia_prerrequisito (
    materia_id BIGINT NOT NULL,
    prerrequisito_id BIGINT NOT NULL,
    PRIMARY KEY (materia_id,prerrequisito_id),
    CONSTRAINT fk_prerrequisito_materia FOREIGN KEY (materia_id) REFERENCES materia_catalogo(id),
    CONSTRAINT fk_prerrequisito_requerido FOREIGN KEY (prerrequisito_id) REFERENCES materia_catalogo(id),
    CHECK (materia_id <> prerrequisito_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS perfil_estudiante (
    estudiante_id BIGINT PRIMARY KEY,
    codigo_estudiantil VARCHAR(32) NULL UNIQUE,
    correo VARCHAR(254) NULL UNIQUE,
    telefono VARCHAR(32) NULL,
    malla_id BIGINT NULL,
    nivel_referencia TINYINT UNSIGNED NULL,
    estado ENUM('ACTIVO','INACTIVO','EGRESADO','RETIRADO') NOT NULL DEFAULT 'ACTIVO',
    registrado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_perfil_estudiante_cuenta FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    CONSTRAINT fk_perfil_estudiante_malla FOREIGN KEY (malla_id) REFERENCES malla_curricular(id),
    CHECK (nivel_referencia IS NULL OR nivel_referencia BETWEEN 1 AND 16)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS perfil_docente (
    docente_id BIGINT PRIMARY KEY,
    correo VARCHAR(254) NULL UNIQUE,
    telefono VARCHAR(32) NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_perfil_docente_cuenta FOREIGN KEY (docente_id) REFERENCES cuenta(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS oferta_horario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    materia_id BIGINT NOT NULL,
    paralelo VARCHAR(12) NOT NULL,
    dia_semana TINYINT UNSIGNED NOT NULL,
    hora_inicio TIME NOT NULL,
    hora_fin TIME NOT NULL,
    aula VARCHAR(80) NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_oferta_horario_materia FOREIGN KEY (materia_id) REFERENCES materia(id),
    UNIQUE KEY uq_oferta_bloque (materia_id,paralelo,dia_semana,hora_inicio,hora_fin),
    CHECK (dia_semana BETWEEN 1 AND 5),
    CHECK (hora_fin > hora_inicio)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS matricula_periodo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    estudiante_id BIGINT NOT NULL,
    periodo_id BIGINT NOT NULL,
    estado ENUM('BORRADOR','PENDIENTE','CONFIRMADA','RECHAZADA','ANULADA') NOT NULL DEFAULT 'BORRADOR',
    creada_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmada_en DATETIME NULL,
    UNIQUE KEY uq_matricula_estudiante_periodo (estudiante_id,periodo_id),
    CONSTRAINT fk_matricula_periodo_estudiante FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    CONSTRAINT fk_matricula_periodo_periodo FOREIGN KEY (periodo_id) REFERENCES semestre_academico(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS matricula_periodo_detalle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    matricula_id BIGINT NOT NULL,
    materia_id BIGINT NOT NULL,
    paralelo VARCHAR(12) NOT NULL,
    estado ENUM('PENDIENTE','CONFIRMADA','RECHAZADA','ANULADA') NOT NULL DEFAULT 'PENDIENTE',
    creada_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_detalle_matricula_oferta (matricula_id,materia_id),
    CONSTRAINT fk_detalle_matricula FOREIGN KEY (matricula_id) REFERENCES matricula_periodo(id),
    CONSTRAINT fk_detalle_oferta FOREIGN KEY (materia_id) REFERENCES materia(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS historial_academico (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    estudiante_id BIGINT NOT NULL,
    materia_catalogo_id BIGINT NOT NULL,
    periodo_id BIGINT NULL,
    materia_oferta_id BIGINT NULL,
    estado ENUM('APROBADA','REPROBADA','RETIRADA','HOMOLOGADA') NOT NULL,
    calificacion DECIMAL(5,2) NULL,
    registrada_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_historial_estudiante_materia_periodo (estudiante_id,materia_catalogo_id,periodo_id),
    CONSTRAINT fk_historial_estudiante FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    CONSTRAINT fk_historial_catalogo FOREIGN KEY (materia_catalogo_id) REFERENCES materia_catalogo(id),
    CONSTRAINT fk_historial_periodo FOREIGN KEY (periodo_id) REFERENCES semestre_academico(id),
    CONSTRAINT fk_historial_oferta FOREIGN KEY (materia_oferta_id) REFERENCES materia(id),
    CHECK (calificacion IS NULL OR calificacion BETWEEN 0 AND 100)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS solicitud_justificacion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    clase_id BIGINT NOT NULL,
    estudiante_id BIGINT NOT NULL,
    motivo VARCHAR(500) NOT NULL,
    documento MEDIUMBLOB NULL,
    estado ENUM('PENDIENTE','APROBADA','RECHAZADA') NOT NULL DEFAULT 'PENDIENTE',
    solicitada_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revisada_por BIGINT NULL,
    revisada_en DATETIME NULL,
    respuesta VARCHAR(500) NULL,
    UNIQUE KEY uq_solicitud_clase_estudiante (clase_id,estudiante_id),
    CONSTRAINT fk_solicitud_clase FOREIGN KEY (clase_id) REFERENCES clase(id),
    CONSTRAINT fk_solicitud_estudiante FOREIGN KEY (estudiante_id) REFERENCES cuenta(id),
    CONSTRAINT fk_solicitud_revisor FOREIGN KEY (revisada_por) REFERENCES cuenta(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS auditoria_evento (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NULL,
    accion VARCHAR(80) NOT NULL,
    entidad VARCHAR(80) NOT NULL,
    referencia VARCHAR(80) NULL,
    creado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auditoria_actor FOREIGN KEY (actor_id) REFERENCES cuenta(id),
    KEY idx_auditoria_fecha (creado_en),
    KEY idx_auditoria_actor (actor_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS configuracion_academica (
    clave VARCHAR(80) PRIMARY KEY,
    valor VARCHAR(255) NOT NULL,
    actualizado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;
INSERT IGNORE INTO configuracion_academica(clave,valor) VALUES
    ('repetir_materia_aprobada','false'),
    ('asistencia_umbral_porcentaje',''),
    ('max_creditos_matricula',''),
    ('asistencia_atraso_cuenta','true'),
    ('asistencia_justificada_excluida','true');

-- Los períodos migrados siguen habilitados para no bloquear el flujo existente.
ALTER TABLE semestre_academico
    ADD COLUMN IF NOT EXISTS matricula_desde DATE NULL,
    ADD COLUMN IF NOT EXISTS matricula_hasta DATE NULL,
    ADD COLUMN IF NOT EXISTS estado VARCHAR(24) NOT NULL DEFAULT 'MATRICULA_ABIERTA';

ALTER TABLE materia
    ADD COLUMN IF NOT EXISTS catalogo_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS activa BOOLEAN NOT NULL DEFAULT TRUE;

INSERT IGNORE INTO malla_curricular(nombre,version,es_demostracion)
VALUES ('Malla curricular existente','Migrada',FALSE);
SET @malla_migrada = (SELECT id FROM malla_curricular WHERE nombre='Malla curricular existente' AND version='Migrada' LIMIT 1);
INSERT IGNORE INTO materia_catalogo(malla_id,codigo,nombre,nivel,activa)
SELECT @malla_migrada,codigo,nombre,nivel,TRUE FROM materia;
UPDATE materia m JOIN materia_catalogo c ON c.codigo=m.codigo AND c.malla_id=@malla_migrada
SET m.catalogo_id=c.id WHERE m.catalogo_id IS NULL;

SET @indice_codigo = (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='materia' AND COLUMN_NAME='codigo'
    GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE)=0 AND COUNT(*)=1 LIMIT 1
);
SET @ddl_codigo = IF(@indice_codigo IS NULL,'SELECT 1',CONCAT('ALTER TABLE materia DROP INDEX `',@indice_codigo,'`'));
PREPARE stmt_codigo FROM @ddl_codigo;
EXECUTE stmt_codigo;
DEALLOCATE PREPARE stmt_codigo;
SET @indice_normal = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='materia' AND INDEX_NAME='idx_materia_codigo'
);
SET @ddl_codigo = IF(@indice_normal=0,'CREATE INDEX idx_materia_codigo ON materia(codigo)','SELECT 1');
PREPARE stmt_codigo FROM @ddl_codigo;
EXECUTE stmt_codigo;
DEALLOCATE PREPARE stmt_codigo;

ALTER TABLE clase MODIFY COLUMN paralelo VARCHAR(12) NOT NULL DEFAULT 'A';
ALTER TABLE clase ADD COLUMN IF NOT EXISTS aula VARCHAR(80) NULL;
ALTER TABLE matricula MODIFY COLUMN paralelo VARCHAR(12) NULL;

CREATE INDEX IF NOT EXISTS idx_clase_fin ON clase(fin,cancelada);
CREATE INDEX IF NOT EXISTS idx_matricula_estudiante_estado ON matricula(estudiante_id,estado);
CREATE INDEX IF NOT EXISTS idx_historial_estudiante_estado ON historial_academico(estudiante_id,estado);

CREATE TABLE IF NOT EXISTS materia_paralelo (
    materia_id BIGINT NOT NULL,
    codigo VARCHAR(12) NOT NULL,
    cupo_maximo SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (materia_id,codigo),
    CONSTRAINT fk_materia_paralelo_materia FOREIGN KEY (materia_id) REFERENCES materia(id),
    CHECK (cupo_maximo <= 500)
) ENGINE=InnoDB;
INSERT IGNORE INTO materia_paralelo(materia_id,codigo,cupo_maximo)
SELECT id,'A',cupos_a FROM materia;
INSERT IGNORE INTO materia_paralelo(materia_id,codigo,cupo_maximo)
SELECT id,'B',cupos_b FROM materia;

INSERT IGNORE INTO perfil_estudiante(estudiante_id,malla_id,nivel_referencia)
SELECT c.id,@malla_migrada,m.nivel FROM cuenta c LEFT JOIN materia m ON m.id=(
    SELECT MIN(m2.id) FROM materia m2 JOIN matricula e ON e.materia_id=m2.id WHERE e.estudiante_id=c.id
) WHERE c.rol='ESTUDIANTE';
INSERT IGNORE INTO perfil_docente(docente_id) SELECT id FROM cuenta WHERE rol='DOCENTE';

-- Conserva una traza relacional de las matrículas existentes, sin cambiar sus estados.
INSERT IGNORE INTO matricula_periodo(estudiante_id,periodo_id,estado,creada_en,confirmada_en)
SELECT e.estudiante_id,m.periodo_id,
    CASE WHEN SUM(e.estado IN ('APROBADA','RETIRADA'))>0 THEN 'CONFIRMADA'
         WHEN SUM(e.estado='PENDIENTE')>0 THEN 'PENDIENTE'
         WHEN SUM(e.estado='RECHAZADA')>0 THEN 'RECHAZADA'
         ELSE 'BORRADOR' END,
    CURRENT_TIMESTAMP,
    CASE WHEN SUM(e.estado IN ('APROBADA','RETIRADA'))>0 THEN CURRENT_TIMESTAMP ELSE NULL END
FROM matricula e JOIN materia m ON m.id=e.materia_id WHERE m.periodo_id IS NOT NULL
GROUP BY e.estudiante_id,m.periodo_id;
INSERT IGNORE INTO matricula_periodo_detalle(matricula_id,materia_id,paralelo,estado)
SELECT mp.id,e.materia_id,COALESCE(e.paralelo,'A'),
    CASE WHEN e.estado IN ('APROBADA','RETIRADA') THEN 'CONFIRMADA'
         WHEN e.estado='RECHAZADA' THEN 'RECHAZADA'
         WHEN e.estado='PENDIENTE' THEN 'PENDIENTE'
         ELSE 'ANULADA' END
FROM matricula e JOIN materia m ON m.id=e.materia_id
JOIN matricula_periodo mp ON mp.estudiante_id=e.estudiante_id AND mp.periodo_id=m.periodo_id
WHERE m.periodo_id IS NOT NULL;

