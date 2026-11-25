#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ ! -f servidor.properties ]; then
  echo 'Crea servidor.properties a partir de servidor.properties.ejemplo' >&2
  exit 1
fi
if [ ! -f AsistenciaEstudiantilWeb.jar ]; then
  echo 'No existe AsistenciaEstudiantilWeb.jar. Compila el proyecto primero.' >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo 'No se encontro Java 17 o superior en PATH.' >&2
  exit 1
fi
if [ -z "${ASISTENCIA_CLAVE_FACIAL:-}" ]; then
  echo 'Configura ASISTENCIA_CLAVE_FACIAL con la misma clave usada para cifrar los perfiles.' >&2
  exit 1
fi
if [ -n "${OPENCV_NATIVE_DIR:-}" ]; then
  exec java "-Djava.library.path=$OPENCV_NATIVE_DIR" -cp 'AsistenciaEstudiantilWeb.jar:lib/*' ec.asistencia.Gestion --servidor
fi
exec java -cp 'AsistenciaEstudiantilWeb.jar:lib/*' ec.asistencia.Gestion --servidor