USE asistencia;

ALTER TABLE cuenta
    ADD COLUMN IF NOT EXISTS usuario_institucional VARCHAR(64) NULL,
    ADD UNIQUE INDEX IF NOT EXISTS uq_cuenta_usuario_institucional (usuario_institucional);

ALTER TABLE matricula_periodo_detalle
    ADD COLUMN IF NOT EXISTS terminos_version VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS terminos_aceptados_en DATETIME NULL,
    ADD COLUMN IF NOT EXISTS legalizar_desde DATETIME NULL,
    ADD INDEX IF NOT EXISTS idx_detalle_legalizacion (estado,legalizar_desde);
