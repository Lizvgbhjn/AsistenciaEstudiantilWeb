package ec.asistencia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Generador PDF pequeño sin dependencia externa, con fuente base WinAnsi. */
final class PdfBasico {
    private static final Charset PDF_TEXT=Charset.forName("windows-1252");
    private PdfBasico() {}

    static byte[] generar(String titulo,List<String> lineas) throws IOException {
        List<List<String>> paginas=new ArrayList<>();
        for(int inicio=0;inicio<lineas.size();inicio+=48)
            paginas.add(lineas.subList(inicio,Math.min(inicio+48,lineas.size())));
        if(paginas.isEmpty()) paginas.add(List.of("Sin registros para mostrar."));
        int n=paginas.size();
        List<byte[]> objetos=new ArrayList<>();
        objetos.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids=new StringBuilder();
        for(int i=0;i<n;i++)kids.append(4+2*i).append(" 0 R ");
        objetos.add(bytes("<< /Type /Pages /Kids ["+kids+"] /Count "+n+" >>"));
        objetos.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        for(int i=0;i<n;i++){
            int page=4+2*i, stream=page+1;
            objetos.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "+stream+" 0 R >>"));
            byte[] contenido=contenido(titulo,paginas.get(i),i+1,n);
            ByteArrayOutputStream obj=new ByteArrayOutputStream();
            obj.write(bytes("<< /Length "+contenido.length+" >>\nstream\n"));
            obj.write(contenido);obj.write(bytes("\nendstream"));
            objetos.add(obj.toByteArray());
        }
        ByteArrayOutputStream pdf=new ByteArrayOutputStream();
        pdf.write(bytes("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n"));
        long[] offsets=new long[objetos.size()+1];
        for(int i=0;i<objetos.size();i++){
            offsets[i+1]=pdf.size();
            pdf.write(bytes((i+1)+" 0 obj\n"));pdf.write(objetos.get(i));pdf.write(bytes("\nendobj\n"));
        }
        long xref=pdf.size();pdf.write(bytes("xref\n0 "+offsets.length+"\n0000000000 65535 f \n"));
        for(int i=1;i<offsets.length;i++)pdf.write(bytes(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[i])));
        pdf.write(bytes("trailer\n<< /Size "+offsets.length+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF"));
        return pdf.toByteArray();
    }

    private static byte[] contenido(String titulo,List<String> lineas,int pagina,int total){
        StringBuilder s=new StringBuilder("BT /F1 18 Tf 48 755 Td (").append(escapar(titulo)).append(") Tj ET\n")
                .append("BT /F1 9 Tf 48 735 Td 13 TL\n");
        for(String linea:lineas)s.append('(').append(escapar(linea)).append(") Tj T*\n");
        s.append("ET\nBT /F1 8 Tf 520 24 Td (").append(pagina).append(" / ").append(total).append(") Tj ET");
        return s.toString().getBytes(PDF_TEXT);
    }

    private static String escapar(String texto){
        String limpio=(texto==null?"":texto).replace('\r',' ').replace('\n',' ');
        StringBuilder s=new StringBuilder(Math.min(limpio.length(),180));
        for(int i=0;i<limpio.length()&&s.length()<180;i++){
            char c=limpio.charAt(i);
            if(c=='('||c==')'||c=='\\')s.append('\\');
            if(c>=32)s.append(c);
        }
        return s.toString();
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.ISO_8859_1);}
}
