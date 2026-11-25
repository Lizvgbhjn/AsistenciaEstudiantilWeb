# Asistencia Estudiantil Face ID — versión web

Esta aplicación combina una interfaz web en HTML, CSS y JavaScript con el servidor y la lógica académica en Java. Utiliza MariaDB/MySQL para guardar cuentas, matrículas, clases y asistencias.

## Funciones

- Acceso para administración, docentes y estudiantes, con permisos separados por rol.
- Administración de períodos académicos, materias, parciales, clases, cupos y matrículas.
- Consulta de reportes y justificación de faltas para docentes.
- Solicitud de justificación por el estudiante, con aprobación o rechazo del docente responsable.
- Reserva de cupos, fotografía de matrícula y consulta de horario para estudiantes.
- Reglas curriculares configurables, ventanas de matrícula por período y paralelos configurables.
- Descarga directa de comprobantes y reportes de asistencia en PDF.
- Registro y verificación facial mediante la cámara del navegador.

## Requisitos

- Java JDK 17 o superior para compilar y ejecutar.
- MariaDB 10.11 para las migraciones y las pruebas documentadas.
- Navegador moderno. La cámara funciona desde `localhost` o una conexión HTTPS.
- OpenCV 4.13 y su biblioteca nativa para usar las funciones Face ID. El JAR está en `lib/`; en Windows la DLL predeterminada es `C:\opencv\build\java\x64\opencv_java4130.dll`.

## Instalación en Windows

1. Inicia MariaDB/MySQL y crea la base `asistencia`. Para una base nueva, importa `bd/esquema.sql` desde phpMyAdmin o el cliente SQL.
2. Copia `servidor.properties.ejemplo` como `servidor.properties`. Configura `bd.url`, `bd.usuario` y `bd.clave`. Una contraseña vacía se escribe dejando `bd.clave=` sin comillas. Usa el puerto real del servidor: normalmente `3306`; si Docker publica `3377:3306`, usa `3377`.
3. En una base nueva, importa primero `bd/esquema.sql` y luego `bd/actualizar-sistema-academico.sql`. En una base anterior, conserva tus datos, aplica solo las migraciones antiguas que falten en este orden: `actualizar-apellidos.sql`, `actualizar-matriculas.sql`, `actualizar-paralelos.sql`, `actualizar-semestres.sql`, `actualizar-ofertas-periodo.sql`, `actualizar-jornadas.sql` y `actualizar-faceid.sql`; después ejecuta `actualizar-sistema-academico.sql`. En ambos casos importa al final `bd/actualizar-flujo-matricula.sql`, `bd/actualizar-uta.sql`, `bd/actualizar-parciales-periodo.sql` y `bd/actualizar-clases-horario.sql`. Esta última migración también se aplica a instalaciones existentes. La migración curricular agrega catálogos/perfiles, historial curricular, períodos de matrícula, cupos configurables y solicitudes de justificación. Haz un respaldo antes de migrar y verifica el nombre de la base indicado al inicio del archivo.
4. En PowerShell, configura la clave facial. Si ya hay perfiles faciales, usa la clave con la que se cifraron; cambiarla impide descifrarlos. Para una instalación nueva, genera una clave y guárdala fuera del proyecto:

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
   $env:ASISTENCIA_CLAVE_FACIAL = [Convert]::ToBase64String($bytes)
   ```

   Esa variable dura solo en esa ventana de PowerShell. Configúrala de nuevo al iniciar otra sesión.
5. Si OpenCV está en otra ubicación, indica la carpeta que contiene `opencv_java4130.dll`:

   ```powershell
   $env:OPENCV_NATIVE_DIR = 'C:\ruta\opencv\build\java\x64'
   ```
6. Para compilar desde el código fuente, ejecuta `.\COMPILAR_WEB.bat`. Después inicia el servidor en la misma carpeta y ventana:

   ```powershell
   .\INICIAR_WEB.bat
   ```
7. Abre [http://localhost:18190](http://localhost:18190). Mantén la ventana del servidor abierta mientras uses la aplicación.

En Administración, configura el calendario de matrícula del período. Las reglas de créditos y prerrequisitos se aplican cuando sus valores están cargados en `configuracion_academica` y `materia_prerrequisito`; el proyecto no incluye una malla oficial de FISEI porque debe cargarse desde una fuente institucional vigente. Los nombres/códigos migrados corresponden a tus datos existentes. Para descargar un PDF, usa **Descargar PDF** desde Reportes, Mis asistencias o Mis comprobantes.

Si la base todavía no tiene administrador, crea uno desde PowerShell. La clave administrativa debe tener entre 8 y 128 caracteres:

```powershell
$env:ASISTENCIA_ADMIN_CLAVE = 'UNA_CLAVE_SEGURA_Y_PRIVADA'
java -cp 'AsistenciaEstudiantilWeb.jar;lib/*' ec.asistencia.Gestion --crear-admin admin Administrador
Remove-Item Env:ASISTENCIA_ADMIN_CLAVE
```

El comando también necesita `servidor.properties` y `ASISTENCIA_CLAVE_FACIAL` configurados.

## Linux

Asegúrate de tener Java 17 o superior, configura `servidor.properties` y `ASISTENCIA_CLAVE_FACIAL`. Compila y ejecuta desde la carpeta del proyecto:

```sh
rm -rf out
mkdir out
javac --release 17 -encoding UTF-8 -cp 'lib/*' -d out src/ec/asistencia/*.java
jar cfe AsistenciaEstudiantilWeb.jar ec.asistencia.Gestion -C out .
./iniciar_web.sh
```

Si OpenCV no está en la ruta nativa predeterminada, configura `OPENCV_NATIVE_DIR` con la carpeta que contiene su biblioteca nativa.

## Compilar en NetBeans

El proyecto NetBeans usa Java 17 como nivel de compilación. Para crear el JAR que ejecutan los scripts de esta guía, usa `COMPILAR_WEB.bat`; el JAR del proyecto NetBeans tiene otro nombre y no reemplaza automáticamente `AsistenciaEstudiantilWeb.jar`.

## Despliegue y privacidad

La aplicación necesita un servidor Java y MariaDB; no funciona como sitio estático en GitHub Pages. Para acceso fuera del equipo local, usa un proxy inverso con HTTPS, controles de acceso y una cuenta de base de datos con permisos limitados. No expongas directamente el puerto HTTP ni la base de datos a Internet. Mantén `servidor.properties` y las claves fuera de repositorios públicos.

Al registrar Face ID se guarda una plantilla matemática cifrada y la fecha de consentimiento; no se conserva la foto de registro. Al marcar asistencia se guarda la fotografía de evidencia. Las funciones faciales requieren la biblioteca nativa de OpenCV en el equipo servidor.

## Parciales del período

En **Administración → Parciales**, selecciona un período y configura o edita sus dos parciales. Las fechas se aplican a todas sus ofertas, incluidas las creadas posteriormente. Los cambios conservan las clases y se rechazan si alguna quedaría fuera del nuevo rango. En **Clases** y **Reportes**, selecciona la materia y su parcial.

La migración adopta calendarios anteriores uniformes para Primer y Segundo parcial. Los rangos en conflicto y los parciales con nombres históricos se conservan en las ofertas. Administración puede configurar las fechas comunes de los dos parciales desde el portal.

## Clases desde el horario semanal

Administración define los bloques en **Ofertas académicas → Horarios** y las fechas en **Parciales**. El servidor genera las sesiones futuras del paralelo durante todo el período académico al guardar esa configuración y al iniciar. Las fechas cubiertas por un parcial se agrupan en ese parcial. Las demás aparecen en **Fuera de parciales**. Si cambia el horario, actualiza las sesiones futuras y cancela las que ya no correspondan. Las clases iniciadas y las registradas manualmente se conservan. El docente selecciona **Materia → Parcial** y usa **Editar tema** en la sesión asignada. Para una demostración dentro del período vigente, puede usar **Clases → Iniciar clase ahora**, escribir el tema y elegir el paralelo. La sesión abre asistencia de inmediato durante 30 minutos. Si el paralelo ya tiene una clase en curso, el sistema actualiza su tema.

## Matrícula, historial y Face ID

En **Administración → Estudiantes y docentes**, asigna el **Usuario institucional UTA** de cada cuenta. El portal web exige el correo institucional completo, por ejemplo `jperez@uta.edu.ec`, y la contraseña de la aplicación. Las cuentas de estudiantes y docentes requieren que Administración les asigne el usuario institucional antes de acceder a la web. Administración usa su usuario local. El correo de contacto se gestiona por separado.

En **Mi matrícula**, el estudiante confirma el resumen y acepta los términos. El sistema reserva los cupos, guarda la versión y fecha de aceptación y legaliza automáticamente a partir de las 00:00 del día siguiente en la zona configurada en `servidor.zona`. El servidor revisa vencimientos cada 30 segundos y retoma los pendientes cuando reinicia. El horario confirmado se habilita al legalizar. Administración puede intervenir desde **Matrículas**. Las solicitudes anteriores a esta actualización conservan su estado y se gestionan desde ese módulo.

Desde Matrículas, **Resultado académico** registra el resultado de cursar una asignatura. Este resultado habilita prerrequisitos según el catálogo. Las instalaciones que ejecutaron una versión anterior de la migración curricular deben revisar su historial. Aquella versión podía registrar como aprobadas asignaturas que solo tenían una matrícula legalizada. Los registros previos se conservan para que Administración corrija cada resultado con los datos académicos disponibles.

En **Face ID → Retirar consentimiento**, el estudiante puede eliminar su plantilla. Para registrarla nuevamente, debe dar su consentimiento y resolver un reto nuevo. Las fotos de evidencias ya registradas siguen su gestión de retención en MariaDB. El reto compara una captura frontal y un giro aleatorio del mismo rostro. La detección de vídeo reproducido o cámara virtual queda fuera de las garantías de este mecanismo. Ante un fallo de verificación, el estudiante puede pedir que se revise la falta y adjuntar respaldo una vez cerrada la sesión.

## Datos y pruebas DEMO

Usa una base exclusiva de prueba con los seis archivos SQL de instalación aplicados. Configura una contraseña temporal para el conjunto DEMO en la misma consola:

```powershell
$env:ASISTENCIA_DEMO_CLAVE = 'ClaveTemporalDeDemostracion'
java -cp 'AsistenciaEstudiantilWeb.jar;lib/*' ec.asistencia.Gestion --datos-demo
```

Accesos de demostración: `demo.admin`, `demo.docente1`, `demo.docente2` y las cédulas ficticias `0000000001`, `0000000002` y `0000000003`. Todos usan la clave anterior. Las materias, aulas y sesiones están etiquetadas DEMO.

Inicia el servidor. En otra consola, vuelve a definir `ASISTENCIA_DEMO_CLAVE` y ejecuta:

```powershell
$env:ASISTENCIA_TEST_URL = 'http://127.0.0.1:18190'
node pruebas/integracion.mjs
javac --release 17 -encoding UTF-8 -cp 'AsistenciaEstudiantilWeb.jar;lib/*' -d out pruebas/PruebaFlujoAsistencia.java
java -cp 'out;AsistenciaEstudiantilWeb.jar;lib/*' ec.asistencia.PruebaFlujoAsistencia servidor.properties
```

La prueba HTTP modifica datos DEMO y requiere una base DEMO limpia. La prueba de asistencia revierte sus cambios y usa un descriptor sintético para verificar el cifrado y la lógica académica. La captura de cámara y el reconocimiento con un rostro real requieren una prueba manual.


## Cambios y verificación

- `Academico.java`, `Curriculo.java`, `Reservas.java` y `Base.java` conectan las ofertas de catálogo, el plazo y sus excepciones, el control de cupos y el horario confirmado. La solicitud web se guarda en una transacción. Las reservas del cliente anterior pueden liberarse desde la web.
- `Servidor.java`, `Protocolo.java` y `web/app.js` enlazan los permisos, la auditoría, el resumen de matrícula, las correcciones de asistencia y las descargas PDF. Administración puede consultar el reporte general. El docente consulta sus asignaturas y estudiantes.
- `bd/actualizar-flujo-matricula.sql` agrega la configuración de legalización, excepciones, índices y correcciones auditadas. Las correcciones requieren un motivo y conservan la marcación original. El resultado corregido se aplica a consultas, porcentajes y PDF.
- `DatosDemo.java` y `pruebas/` contienen datos y pruebas reproducibles para una base aislada. Con dos procesos Java que compartan la base DEMO, configura `ASISTENCIA_TEST_SECOND_URL` para verificar la competencia por el último cupo entre procesos. Después de reiniciar el servidor, usa `ASISTENCIA_TEST_RESTART=true` para verificar la persistencia con la misma base.
- La asignación de docentes se realiza por oferta. Para separar docentes de una misma asignatura en un período, configura ofertas distintas vinculadas a la misma entrada de catálogo y cupos únicamente en los paralelos que correspondan a cada docente.

