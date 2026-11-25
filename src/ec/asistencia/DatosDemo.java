package ec.asistencia;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Properties;

/** Seed idempotente y explícitamente no oficial para pruebas locales. */
final class DatosDemo {
    private DatosDemo(){}

    static void ejecutar(Properties configuracion,String clave) throws SQLException {
        Academico.exigir(clave!=null&&clave.length()>=8&&clave.length()<=128,
                "Configura ASISTENCIA_DEMO_CLAVE (8 a 128 caracteres) para crear cuentas DEMO.");
        try(Base bd=new Base(configuracion)){
            bd.iniciar();
            try{
                long admin=cuenta(bd,"demo.admin","DEMO Administrador","Local","ADMIN",clave);
                long docente1=cuenta(bd,"demo.docente1","DEMO Docente","Uno","DOCENTE",clave);
                long docente2=cuenta(bd,"demo.docente2","DEMO Docente","Dos","DOCENTE",clave);
                long estudiante1=cuenta(bd,"0000000001","DEMO Estudiante","Uno","ESTUDIANTE",clave);
                long estudiante2=cuenta(bd,"0000000002","DEMO Estudiante","Dos","ESTUDIANTE",clave);
                long estudiante3=cuenta(bd,"0000000003","DEMO Estudiante","Tres","ESTUDIANTE",clave);
                bd.cambiar("INSERT IGNORE INTO perfil_docente(docente_id) VALUES(?),(?)",docente1,docente2);

                String nombreMalla="DEMO · Ingeniería de Software · NO OFICIAL";
                String[] filaMalla=bd.uno("SELECT id FROM malla_curricular WHERE nombre=? AND version='DEMO-1'",nombreMalla);
                long malla;
                if(filaMalla==null){
                    bd.cambiar("INSERT INTO malla_curricular(nombre,version,estado,es_demostracion) VALUES(?,'DEMO-1','ACTIVA',TRUE)",nombreMalla);
                    malla=lastId(bd);
                }else {malla=Long.parseLong(filaMalla[0]);bd.cambiar("UPDATE malla_curricular SET estado='ACTIVA',es_demostracion=TRUE WHERE id=?",malla);}
                bd.cambiar("UPDATE perfil_estudiante SET malla_id=?,codigo_estudiantil=CASE WHEN codigo_estudiantil IS NULL OR codigo_estudiantil='' THEN CONCAT('DEMO-EST-',LPAD(estudiante_id,3,'0')) ELSE codigo_estudiantil END WHERE estudiante_id IN (?,?,?)",
                        malla,estudiante1,estudiante2,estudiante3);

                LocalDate hoy=LocalDate.now(ZoneId.of(configuracion.getProperty("servidor.zona","America/Guayaquil")));
                String nombrePeriodo="DEMO · Período académico · NO OFICIAL";
                String[] fPeriodo=bd.uno("SELECT id,desde,hasta FROM semestre_academico WHERE nombre=?",nombrePeriodo);
                long periodo;
                LocalDate inicio;
                LocalDate fin;
                if(fPeriodo==null){
                    String[] max=bd.uno("SELECT MAX(hasta) FROM semestre_academico");
                    LocalDate despues=max==null||max[0].isBlank()?hoy.plusDays(1):LocalDate.parse(max[0]).plusDays(1);
                    inicio=despues.isBefore(hoy.plusDays(1))?hoy.plusDays(1):despues;fin=inicio.plusDays(120);
                    bd.cambiar("INSERT INTO semestre_academico(nombre,desde,hasta,matricula_desde,matricula_hasta,estado) VALUES(?,?,?,?,?,'MATRICULA_ABIERTA')",
                            nombrePeriodo,inicio,fin,hoy,hoy.plusDays(30));periodo=lastId(bd);
                }else{
                    periodo=Long.parseLong(fPeriodo[0]);inicio=LocalDate.parse(fPeriodo[1]);fin=LocalDate.parse(fPeriodo[2]);
                    bd.cambiar("UPDATE semestre_academico SET matricula_desde=?,matricula_hasta=?,estado='MATRICULA_ABIERTA' WHERE id=?",
                            hoy,hoy.plusDays(30),periodo);
                }

                long catalogo1=catalogo(bd,malla,"DEMO-SI-001","DEMO · Fundamentos de software",1,4);
                long catalogo2=catalogo(bd,malla,"DEMO-SI-002","DEMO · Taller de software",1,3);
                bd.cambiar("INSERT IGNORE INTO materia_prerrequisito(materia_id,prerrequisito_id) VALUES(?,?)",catalogo2,catalogo1);
                long oferta1=oferta(bd,"DEMO-SI-001","DEMO · Fundamentos de software",docente1,periodo,catalogo1);
                long oferta2=oferta(bd,"DEMO-SI-002","DEMO · Taller de software",docente2,periodo,catalogo2);
                bd.cambiar("INSERT INTO materia_paralelo(materia_id,codigo,cupo_maximo,activo) VALUES(?, 'A',1,TRUE),(?, 'B',2,TRUE) ON DUPLICATE KEY UPDATE cupo_maximo=VALUES(cupo_maximo),activo=TRUE",oferta1,oferta1);
                bd.cambiar("INSERT INTO materia_paralelo(materia_id,codigo,cupo_maximo,activo) VALUES(?, 'A',2,TRUE),(?, 'B',2,TRUE) ON DUPLICATE KEY UPDATE cupo_maximo=VALUES(cupo_maximo),activo=TRUE",oferta2,oferta2);

                long p1=parcial(bd,oferta1,"DEMO · Parcial 1",inicio,inicio.plusDays(45));
                long p2=parcial(bd,oferta2,"DEMO · Parcial 1",inicio,inicio.plusDays(100));
                LocalDate fechaA=proximoDia(inicio.plusDays(8),DayOfWeek.MONDAY);
                LocalDate fechaB=proximoDia(fechaA.plusDays(1),DayOfWeek.TUESDAY);
                clase(bd,p1,"DEMO · Sesión A",fechaA.atTime(LocalTime.of(8,0)),"A");
                clase(bd,p1,"DEMO · Sesión B",fechaB.atTime(LocalTime.of(8,0)),"B");
                clase(bd,p2,"DEMO · Sesión A",proximoDia(fechaB.plusDays(1),DayOfWeek.WEDNESDAY).atTime(LocalTime.of(9,30)),"A");
                clase(bd,p2,"DEMO · Sesión B",proximoDia(fechaB.plusDays(2),DayOfWeek.THURSDAY).atTime(LocalTime.of(9,30)),"B");
                horario(bd,oferta1,"A",1,"08:00:00","09:00:00","LAB DEMO-1");
                horario(bd,oferta1,"B",2,"08:00:00","09:00:00","LAB DEMO-2");
                horario(bd,oferta2,"A",3,"09:30:00","10:30:00","LAB DEMO-1");
                horario(bd,oferta2,"B",4,"09:30:00","10:30:00","LAB DEMO-2");

                matricula(bd,estudiante1,periodo,oferta1,"A","APROBADA",inicio);
                matricula(bd,estudiante2,periodo,oferta1,"B","HABILITADA",inicio);
                matricula(bd,estudiante3,periodo,oferta1,"A","HABILITADA",inicio);
                bd.confirmar();
                System.out.println("Datos locales DEMO creados. No representan malla oficial ni contienen biometría.");
                System.out.println("Accesos: demo.admin, demo.docente1, demo.docente2, y cédulas sintéticas 0000000001-0000000003.");
                System.out.println("Todas las cuentas usan la clave suministrada por ASISTENCIA_DEMO_CLAVE.");
            }catch(SQLException|RuntimeException e){bd.revertir();throw e;}
        }
    }

    private static long cuenta(Base bd,String usuario,String nombre,String apellidos,String rol,String clave)throws SQLException{
        bd.cambiar("INSERT INTO cuenta(usuario,nombre,apellidos,clave,rol) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),nombre=VALUES(nombre),apellidos=VALUES(apellidos),clave=VALUES(clave),rol=VALUES(rol)",
                usuario,nombre,apellidos,Claves.crear(clave),rol);
        long id=lastId(bd);
        if(rol.equals("ESTUDIANTE"))bd.cambiar("INSERT IGNORE INTO perfil_estudiante(estudiante_id) VALUES(?)",id);
        if(rol.equals("DOCENTE"))bd.cambiar("INSERT IGNORE INTO perfil_docente(docente_id) VALUES(?)",id);
        return id;
    }
    private static long catalogo(Base bd,long malla,String codigo,String nombre,int nivel,int creditos)throws SQLException{
        var f=bd.uno("SELECT id FROM materia_catalogo WHERE malla_id=? AND codigo=?",malla,codigo);
        if(f!=null)return Long.parseLong(f[0]);
        bd.cambiar("INSERT INTO materia_catalogo(malla_id,codigo,nombre,nivel,creditos,activa,es_demostracion) VALUES(?,?,?,?,?,TRUE,TRUE)",malla,codigo,nombre,nivel,creditos);
        return lastId(bd);
    }
    private static long oferta(Base bd,String codigo,String nombre,long docente,long periodo,long catalogo)throws SQLException{
        var f=bd.uno("SELECT id FROM materia WHERE catalogo_id=? AND periodo_id=?",catalogo,periodo);
        if(f!=null)return Long.parseLong(f[0]);
        bd.cambiar("INSERT INTO materia(codigo,nombre,docente_id,nivel,periodo_id,catalogo_id,activa) VALUES(?,?,?,?,?,?,TRUE)",codigo,nombre,docente,1,periodo,catalogo);
        return lastId(bd);
    }
    private static long parcial(Base bd,long oferta,String nombre,LocalDate desde,LocalDate hasta)throws SQLException{
        var f=bd.uno("SELECT id FROM parcial WHERE materia_id=? AND nombre=?",oferta,nombre);
        if(f!=null)return Long.parseLong(f[0]);
        bd.cambiar("INSERT INTO parcial(materia_id,nombre,desde,hasta) VALUES(?,?,?,?)",oferta,nombre,desde,hasta);
        return lastId(bd);
    }
    private static void clase(Base bd,long parcial,String tema,LocalDateTime inicio,String paralelo)throws SQLException{
        if(bd.uno("SELECT id FROM clase WHERE parcial_id=? AND paralelo=? AND inicio=?",parcial,paralelo,inicio)!=null)return;
        bd.cambiar("INSERT INTO clase(parcial_id,tema,inicio,fin,tolerancia,paralelo) VALUES(?,?,?,?,5,?)",parcial,tema,inicio,inicio.plusHours(1),paralelo);
    }
    private static void horario(Base bd,long materia,String paralelo,int dia,String inicio,String fin,String aula)throws SQLException{
        bd.cambiar("INSERT IGNORE INTO oferta_horario(materia_id,paralelo,dia_semana,hora_inicio,hora_fin,aula,activo) VALUES(?,?,?,?,?,?,TRUE)",materia,paralelo,dia,LocalTime.parse(inicio),LocalTime.parse(fin),aula);
    }
    private static void matricula(Base bd,long estudiante,long periodo,long oferta,String paralelo,String estado,LocalDate desde)throws SQLException{
        bd.cambiar("INSERT IGNORE INTO matricula(materia_id,estudiante_id,estado,desde,paralelo) VALUES(?,?,?,?,?)",oferta,estudiante,estado,estado.equals("APROBADA")?desde:null,paralelo);
        var f=bd.uno("SELECT id FROM matricula_periodo WHERE estudiante_id=? AND periodo_id=?",estudiante,periodo);
        long header;
        if(f==null){bd.cambiar("INSERT INTO matricula_periodo(estudiante_id,periodo_id,estado,confirmada_en) VALUES(?,?,?,?)",estudiante,periodo,estado.equals("APROBADA")?"CONFIRMADA":"BORRADOR",estado.equals("APROBADA")?LocalDateTime.now():null);header=lastId(bd);}
        else header=Long.parseLong(f[0]);
        String detalle=estado.equals("APROBADA")?"CONFIRMADA":"PENDIENTE";
        bd.cambiar("INSERT IGNORE INTO matricula_periodo_detalle(matricula_id,materia_id,paralelo,estado) VALUES(?,?,?,?)",header,oferta,paralelo,detalle);
    }
    private static long lastId(Base bd)throws SQLException{return Long.parseLong(bd.uno("SELECT LAST_INSERT_ID()")[0]);}
    private static LocalDate proximoDia(LocalDate fecha,DayOfWeek dia){while(fecha.getDayOfWeek()!=dia)fecha=fecha.plusDays(1);return fecha;}
}
