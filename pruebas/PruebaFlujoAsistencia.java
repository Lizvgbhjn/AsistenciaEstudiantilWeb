package ec.asistencia;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import javax.imageio.ImageIO;

public final class PruebaFlujoAsistencia {
    public static void main(String[] args) throws Exception {
        var config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { config.load(reader); }
        try (var bd = new Base(config)) {
            bd.iniciar();
            try {
                probar(bd);
                probarLegalizacion(bd);
            } finally {
                bd.revertir();
            }
        }
    }

    private static void probarLegalizacion(Base bd) throws Exception {
        long alumno = alumno(bd, "0000000002").id();
        var oferta = bd.uno("""
                SELECT m.id,s.id,s.matricula_desde FROM materia m JOIN semestre_academico s ON s.id=m.periodo_id
                WHERE m.codigo='DEMO-SI-001'
                """);
        long materia = Long.parseLong(oferta[0]);
        var solicitud = java.time.LocalDate.parse(oferta[2]).atTime(14, 0);
        var reservas = new Reservas(bd, solicitud);
        reservas.confirmarSeleccion(alumno, Long.parseLong(oferta[1]),
                new String[]{oferta[1], Reservas.TERMINOS_MATRICULA, oferta[0], "B"});
        reservas.legalizarAlCierre(alumno, materia);
        if (!reservas.bloquesConfirmados(alumno).isEmpty()) throw new AssertionError("Legalización antes del cierre");
        var cierre = solicitud.toLocalDate().plusDays(1).atStartOfDay();
        var alCierre = new Reservas(bd, cierre);
        alCierre.legalizarAlCierre(alumno, materia);
        alCierre.legalizarAlCierre(alumno, materia);
        var fila = bd.uno("""
                SELECT e.estado,e.desde,d.terminos_version,d.terminos_aceptados_en,mp.estado,mp.confirmada_en
                FROM matricula e JOIN matricula_periodo mp ON mp.estudiante_id=e.estudiante_id
                JOIN matricula_periodo_detalle d ON d.matricula_id=mp.id AND d.materia_id=e.materia_id
                WHERE e.estudiante_id=? AND e.materia_id=?
                """, alumno, materia);
        if (!fila[0].equals("APROBADA") || !fila[1].equals(cierre.toLocalDate().toString())
                || !fila[2].equals(Reservas.TERMINOS_MATRICULA) || fila[3].isBlank()
                || !fila[4].equals("CONFIRMADA") || fila[5].isBlank()
                || alCierre.bloquesConfirmados(alumno).isEmpty()) {
            throw new AssertionError("Legalización y aceptación: " + Arrays.toString(fila));
        }
        var auditoria = bd.uno("SELECT COUNT(*) FROM auditoria_evento WHERE accion='legalizarAlCierre' AND referencia=?",
                alumno + ":" + materia);
        if (!auditoria[0].equals("1")) throw new AssertionError("Legalización repetida");
        System.out.println("OK aceptación, legalización al cierre, horario y auditoría sin duplicados");
    }

    private static void probar(Base bd) throws Exception {
        var clase = bd.uno("""
                SELECT c.id,p.id,m.id,m.docente_id,c.inicio,c.fin
                FROM materia m JOIN parcial p ON p.materia_id=m.id JOIN clase c ON c.parcial_id=p.id
                WHERE m.codigo='DEMO-SI-001' AND c.paralelo='A' LIMIT 1
                """);
        var presente = alumno(bd, "0000000001");
        var ausente = alumno(bd, "0000000003");
        var sinMatricula = alumno(bd, "0000000002");
        var docente = new Academico.Usuario(Long.parseLong(clase[3]),"DOCENTE","Docente DEMO");
        var inicio = LocalDateTime.parse(clase[4].replace(' ','T'));
        var fin = LocalDateTime.parse(clase[5].replace(' ','T'));

        // Descriptor artificial inyectado en la capa académica para aislar horario y persistencia.
        // El reto de cámara se verifica aparte y no se simula aquí.
        byte[] descriptor = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).putFloat(1).array();
        String muestra = Base64.getEncoder().encodeToString(descriptor);
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12,12,BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        String[] datos = {clase[0],muestra,Base64.getEncoder().encodeToString(jpeg.toByteArray())};
        var abierto = new Academico(bd,inicio.plusMinutes(15));
        abierto.ejecutar(presente,"guardarPerfilFacial",new String[]{muestra});
        if (!Arrays.equals(descriptor,bd.obtenerPerfilFacial(presente.id()))) throw new AssertionError("Cifrado facial");
        rechazar(new Academico(bd,inicio.minusMinutes(1)),presente,datos,"fuera de su horario");
        rechazar(abierto,sinMatricula,datos,"matrícula legalizada");
        if (!abierto.ejecutar(presente,"marcar",datos).get(0)[0].contains("ATRASO")) throw new AssertionError("Atraso");
        if (!abierto.ejecutar(presente,"marcar",datos).get(0)[0].contains("Ya consta")) throw new AssertionError("Duplicado");

        bd.cambiar("""
                UPDATE matricula SET estado='APROBADA',paralelo='A',desde=?,hasta=NULL
                WHERE estudiante_id=? AND materia_id=?
                """,inicio.toLocalDate(),ausente.id(),Long.parseLong(clase[2]));
        var cerrado = new Academico(bd,fin.plusMinutes(1));
        var total = cerrado.ejecutar(ausente,"resumenAsistencia",new String[]{clase[2]}).get(0);
        if (!"1".equals(total[3])) throw new AssertionError(Arrays.toString(total));
        cerrado.ejecutar(ausente,"solicitarJustificacion",new String[]{clase[0],"Motivo DEMO de integración"});
        var solicitud = bd.uno("SELECT id FROM solicitud_justificacion WHERE clase_id=? AND estudiante_id=?",
                Long.parseLong(clase[0]),ausente.id());
        cerrado.ejecutar(docente,"resolverJustificacion",new String[]{solicitud[0],"APROBADA","Verificado"});
        total = cerrado.ejecutar(ausente,"resumenAsistencia",new String[]{clase[2]}).get(0);
        if (!"0".equals(total[3]) || !"1".equals(total[4])) throw new AssertionError(Arrays.toString(total));
        total = cerrado.ejecutar(presente,"resumenAsistencia",new String[]{clase[2]}).get(0);
        if (!"1".equals(total[2]) || !"100.0".equals(total[5])) throw new AssertionError(Arrays.toString(total));
        cerrado.ejecutar(docente,"corregirAsistencia",new String[]{clase[1],clase[0],Long.toString(presente.id()),"ASISTENCIA","Revisión de registro DEMO"});
        total = cerrado.ejecutar(presente,"resumenAsistencia",new String[]{clase[2]}).get(0);
        if (!"1".equals(total[1]) || !"0".equals(total[2])) throw new AssertionError(Arrays.toString(total));
        var original = bd.uno("SELECT estado FROM asistencia_registro WHERE clase_id=? AND estudiante_id=?",
                Long.parseLong(clase[0]),presente.id());
        if (!"ATRASO".equals(original[0])) throw new AssertionError("Marcación original");
        System.out.println("OK cifrado y flujo académico de asistencia, atraso, faltas, porcentajes y justificación");
    }

    private static Academico.Usuario alumno(Base bd,String usuario) throws Exception {
        return new Academico.Usuario(Long.parseLong(bd.uno("SELECT id FROM cuenta WHERE usuario=?",usuario)[0]),
                "ESTUDIANTE",usuario);
    }

    private static void rechazar(Academico app, Academico.Usuario alumno, String[] datos, String texto) throws Exception {
        try {
            app.ejecutar(alumno,"marcar",datos);
            throw new AssertionError("Se esperaba rechazo: " + texto);
        } catch (IllegalArgumentException esperado) {
            if (!esperado.getMessage().contains(texto)) throw esperado;
        }
    }
}

