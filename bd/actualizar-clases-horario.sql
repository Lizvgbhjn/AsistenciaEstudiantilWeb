USE asistencia;

ALTER TABLE clase
    ADD COLUMN IF NOT EXISTS horario_oferta_id BIGINT NULL,
    ADD INDEX IF NOT EXISTS idx_clase_horario (horario_oferta_id,inicio);
