@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>&1
if errorlevel 1 (
  echo No se encontro javac. Instala un JDK 17 o superior y configura PATH.
  pause
  exit /b 1
)
where jar >nul 2>&1
if errorlevel 1 (
  echo No se encontro jar. Instala un JDK completo, no solo el JRE.
  pause
  exit /b 1
)
if exist out rmdir /s /q out
if exist AsistenciaEstudiantilWeb.nuevo.jar del /q AsistenciaEstudiantilWeb.nuevo.jar
mkdir out
javac --release 17 -encoding UTF-8 -cp "lib/*" -d out src\ec\asistencia\*.java
if errorlevel 1 goto error
jar cfe AsistenciaEstudiantilWeb.nuevo.jar ec.asistencia.Gestion -C out .
if errorlevel 1 goto error
move /y AsistenciaEstudiantilWeb.nuevo.jar AsistenciaEstudiantilWeb.jar >nul
if errorlevel 1 goto jar_en_uso
echo Compilacion terminada correctamente.
exit /b 0
:jar_en_uso
echo No se pudo reemplazar el JAR. Cierra el servidor y vuelve a compilar.
exit /b 1
:error
echo La compilacion fallo. Revisa los errores anteriores.
pause
exit /b 1