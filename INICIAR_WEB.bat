@echo off
setlocal
cd /d "%~dp0"
if not exist "servidor.properties" (
  echo Falta servidor.properties. Copia servidor.properties.ejemplo y configura MariaDB.
  pause
  exit /b 1
)
if not exist "AsistenciaEstudiantilWeb.jar" (
  echo Falta AsistenciaEstudiantilWeb.jar. Ejecuta COMPILAR_WEB.bat primero.
  pause
  exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
  echo No se encontro Java. Instala Java 17 o superior y configura PATH.
  pause
  exit /b 1
)
if "%ASISTENCIA_CLAVE_FACIAL%"=="" (
  echo Falta ASISTENCIA_CLAVE_FACIAL. Consulta README_WEB.md.
  pause
  exit /b 1
)
if "%OPENCV_NATIVE_DIR%"=="" set "OPENCV_NATIVE_DIR=C:\opencv\build\java\x64"
echo Abre http://localhost:18190 cuando el servidor este listo.
java -Djava.library.path="%OPENCV_NATIVE_DIR%" -cp "AsistenciaEstudiantilWeb.jar;lib/*" ec.asistencia.Gestion --servidor
pause