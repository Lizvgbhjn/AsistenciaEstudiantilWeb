USE asistencia;

CREATE TABLE IF NOT EXISTS parcial_periodo (
    periodo_id BIGINT NOT NULL,
    nombre VARCHAR(60) NOT NULL,
    desde DATE NOT NULL,
    hasta DATE NOT NULL,
    PRIMARY KEY (periodo_id,nombre),
    FOREIGN KEY (periodo_id) REFERENCES semestre_academico(id),
    CHECK (hasta >= desde)
) ENGINE=InnoDB;

-- Adoptar solo rangos uniformes, dentro del período y compatibles entre sí.
INSERT IGNORE INTO parcial_periodo(periodo_id,nombre,desde,hasta)
SELECT rangos.periodo_id,rangos.nombre,rangos.desde,rangos.hasta
FROM (
    SELECT m.periodo_id,p.nombre,MIN(p.desde) AS desde,MIN(p.hasta) AS hasta
    FROM parcial p JOIN materia m ON m.id=p.materia_id
    JOIN semestre_academico s ON s.id=m.periodo_id
    WHERE p.nombre IN ('Primer parcial','Segundo parcial')
    GROUP BY m.periodo_id,p.nombre,s.desde,s.hasta
    HAVING MIN(p.desde)=MAX(p.desde) AND MIN(p.hasta)=MAX(p.hasta)
       AND MIN(p.desde)>=s.desde AND MAX(p.hasta)<=s.hasta
) rangos
WHERE NOT EXISTS (
    SELECT 1 FROM parcial otro JOIN materia oferta ON oferta.id=otro.materia_id
    WHERE oferta.periodo_id=rangos.periodo_id AND (
        (rangos.nombre='Primer parcial' AND otro.nombre='Segundo parcial' AND otro.desde<=rangos.hasta)
        OR (rangos.nombre='Segundo parcial' AND otro.nombre='Primer parcial' AND otro.hasta>=rangos.desde)
    )
);

INSERT IGNORE INTO parcial(materia_id,nombre,desde,hasta)
SELECT m.id,p.nombre,p.desde,p.hasta
FROM materia m JOIN parcial_periodo p ON p.periodo_id=m.periodo_id;
