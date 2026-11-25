import assert from 'node:assert/strict';

const base = process.env.ASISTENCIA_TEST_URL || 'http://127.0.0.1:48219';
const secondServer = process.env.ASISTENCIA_TEST_SECOND_URL || base;
const secret = process.env.ASISTENCIA_DEMO_CLAVE;
assert.ok(secret, 'Define ASISTENCIA_DEMO_CLAVE para una base DEMO nueva.');

async function request(action, token, ...fields) {
  return requestAt(base, action, token, ...fields);
}

async function requestAt(server, action, token, ...fields) {
  const values = [action, token, ...fields].map(value => Buffer.from(String(value)));
  const body = Buffer.alloc(8 + values.reduce((size,value) => size + 4 + value.length, 0));
  body.writeInt32BE(1);
  body.writeInt32BE(values.length, 4);
  let offset = 8;
  for (const value of values) {
    body.writeInt32BE(value.length, offset);
    value.copy(body, offset + 4);
    offset += 4 + value.length;
  }
  const res = await fetch(server + '/asistencia', {
    method: 'POST', headers: {'Content-Type':'application/octet-stream'}, body,
  });
  const data = Buffer.from(await res.arrayBuffer());
  offset = 0;
  function int() { const value = data.readInt32BE(offset); offset += 4; return value; }
  const result = Array.from({length:int()}, () => Array.from({length:int()}, () => {
    const size = int();
    const value = data.toString('utf8', offset, offset + size);
    offset += size;
    return value;
  }));
  return {ok:res.ok, result};
}

async function api(...args) {
  const {ok,result} = await request(...args);
  assert.ok(ok, JSON.stringify(result));
  return result;
}

async function reject(pattern, ...args) {
  const {ok,result} = await request(...args);
  assert.equal(ok,false, JSON.stringify(result));
  assert.match(result[0][0],pattern);
}

async function pdf(token, query, expected = 200) {
  const res = await fetch(base + '/api/reportes?' + query, {headers:{'X-Asistencia-Sesion':token}});
  assert.equal(res.status,expected);
  if (res.ok) assert.match(Buffer.from(await res.arrayBuffer()).toString('latin1'), /^%PDF-/);
}

const [[admin]] = await api('ingresarAdmin','','demo.admin',secret);
const [[teacher]] = await api('ingresarDocente','','demo.docente1',secret);
const [[student1]] = await api('ingresarEstudiante','','0000000001',secret);
const [[student2]] = await api('ingresarEstudiante','','0000000002',secret);
const [[student3]] = await api('ingresarEstudiante','','0000000003',secret);
if (process.env.ASISTENCIA_TEST_RESTART === 'true') {
  const deadline = Date.now() + 8000;
  do {
    const bloques = await api('bloquesConfirmados',student2);
    const periodos = await api('misPeriodosMatricula',student2);
    if (bloques.some(row => row[0] === 'DEMO-SI-002') && periodos.some(row => row[2] === 'CONFIRMADA')) {
      console.log('OK persistencia después de reiniciar');
      process.exit(0);
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  } while (Date.now() < deadline);
  assert.fail('No aparece la matrícula confirmada después del reinicio.');
}

const offers = await api('materias',admin);
const first = offers.find(row => row[1] === 'DEMO-SI-001');
const second = offers.find(row => row[1] === 'DEMO-SI-002');
assert.ok(first && second);
const [offer1,,,,,,period] = first;
const [offer2] = second;
const seats = () => api('paralelos',admin,offer1);
const [sessions, groups, schedule] = await Promise.all([
  api('ofertaMatricula',student2,offer1),
  api('paralelos',student2,offer1),
  api('horariosOferta',student2,offer1),
]);
assert.ok(sessions.some(row => row[1]==='A' && row[6]==='0'));
assert.ok(sessions.some(row => row[1]==='B' && row[6]==='2'));
assert.ok(groups.some(row => row[0]==='B'));
assert.ok(schedule.some(row => row[1]==='B'));
console.log('OK selección de materia, sesiones, cupos y horarios');
const studentAccounts = await api('cuentas',admin);
const demoStudent = studentAccounts.find(row => row[2]==='0000000003');

await reject(/registra primero/i,'desafioRostro',student2,'ASISTENCIA');
const [[reto]] = await api('desafioRostro',student2,'REGISTRO');
await reject(/prueba facial venció/i,'validarRostro',student1,reto,'','');
await reject(/JPEG/i,'validarRostro',student2,reto,'','');
await reject(/permiso/i,'cuentas',student1);
await reject(/permiso/i,'bloquesConfirmados',teacher);
await reject(/permiso/i,'guardarConfiguracionAcademica',teacher,'','false','','true','true');
await pdf(teacher,'tipo=asistencia&materia='+offer2,403);
await pdf(student1,'tipo=asistencia&general=true',403);
await api('guardarCuenta',admin,'','ESTUDIANTE','0000000019','DEMO','Pruebas',secret,
  '','pruebas@example.test','',demoStudent[9],'1','ACTIVO','demo.pruebas19');
const [[student4]] = await api('ingresarEstudianteInstitucional','','Demo.Pruebas19@UTA.EDU.EC',secret);
await reject(/correo institucional/i,'ingresarEstudianteInstitucional','','demo.pruebas19',secret);
await reject(/correo institucional/i,'ingresarEstudianteInstitucional','','0000000019',secret);
await reject(/incorrectos/i,'ingresarDocenteInstitucional','','demo.pruebas19@uta.edu.ec',secret);
await api('ingresarEstudiante','','demo.pruebas19',secret);
await reject(/incorrectos/i,'ingresarDocente','','demo.pruebas19',secret);
await reject(/incorrectos/i,'ingresarEstudiante','','0000000019',secret);
console.log('OK acceso por rol, alta de estudiante y límites de acceso');

await reject(/términos/i,'confirmarSeleccion',student3,period,offer1,'A');
await reject(/términos/i,'confirmarSeleccion',student3,period,'matricula-v0',offer1,'A');
await reject(/cupos/i,'confirmarSeleccion',student3,period,'matricula-v1',offer1,'A');
await reject(/prerrequisito/i,'confirmarSeleccion',student2,period,'matricula-v1',offer2,'A');
await reject(/duplicada/i,'confirmarSeleccion',student2,period,'matricula-v1',
  ...Array.from({length:40}, () => [offer1,'B']).flat());
const before = await seats();
await reject(/prerrequisito/i,'confirmarSeleccion',student2,period,'matricula-v1',offer1,'B',offer2,'A');
assert.deepEqual(await seats(),before);
assert.ok(!(await api('misPeriodosMatricula',student2)).some(row => row[2]==='PENDIENTE'));
console.log('OK cupos, prerrequisito, duplicados y reversión de selección completa');

await api('configurarParalelo',admin,offer1,'B','1');
const secondLogin = await requestAt(secondServer,'ingresarEstudiante','','demo.pruebas19',secret);
assert.ok(secondLogin.ok);
const [[student4Token]] = secondLogin.result;
const race = await Promise.all([
  request('confirmarSeleccion',student2,period,'matricula-v1',offer1,'B'),
  requestAt(secondServer,'confirmarSeleccion',student4Token,period,'matricula-v1',offer1,'B'),
]);
assert.equal(race.filter(row=>row.ok).length,1);
assert.match(race.find(row => !row.ok).result[0][0],/cupos/i);
assert.equal((await seats()).find(row => row[0]==='B')[2],'1');
const winner = race[0].ok ? student2 : student4;
const winnerAccount = (await api('cuentas',admin)).find(row => row[2] === (race[0].ok ? '0000000002' : '0000000019'));
const [voucher] = (await api('misPeriodosMatricula',winner)).filter(row => row[2]==='PENDIENTE');
assert.ok(voucher);
await pdf(winner,'tipo=matricula&periodo='+voucher[0]);
await pdf(student3,'tipo=matricula&periodo='+voucher[0],403);
await api('matricular',admin,offer1,winnerAccount[0],'LEGALIZAR');
assert.ok((await api('bloquesConfirmados',winner)).some(row=>row[2]==='B'));
await reject(/disponible/i,'confirmarSeleccion',winner,period,'matricula-v1',offer1,'B');

if (!race[0].ok) {
  await api('configurarParalelo',admin,offer1,'B','2');
  await api('confirmarSeleccion',student2,period,'matricula-v1',offer1,'B');
  const id = studentAccounts.find(row => row[2]==='0000000002')[0];
  await api('matricular',admin,offer1,id,'LEGALIZAR');
}
const teacherId = studentAccounts.find(row => row[2]==='demo.docente1')[0];
assert.ok(teacherId);
const schedules = await api('horariosOferta',admin,offer2);
const current = schedules.find(row => row[1]==='A');
await api('guardarHorarioOferta',admin,current[0],offer2,'A','2','08:00','09:00','LAB DEMO-3','true');
const studentId = studentAccounts.find(row => row[2]==='0000000002')[0];
await api('guardarResultadoAcademico',admin,studentId,offer1,'APROBADA','85');
await reject(/se cruza/i,'confirmarSeleccion',student2,period,'matricula-v1',offer2,'A');
await api('confirmarSeleccion',student2,period,'matricula-v1',offer2,'B');
await api('matricular',admin,offer2,studentId,'LEGALIZAR');
await api('guardarHorarioOferta',admin,current[0],offer2,'A',current[2],current[3],current[4],current[5],'true');
const horariosAntes = await api('horariosOferta',admin,offer2);
const bloqueB = horariosAntes.find(r=>r[1]==='B');
await reject(/cruza para estudiantes/i,'guardarHorarioOferta',admin,bloqueB[0],offer2,'B','2','08:00','09:00','LAB PRUEBA CRUCE','true');
assert.deepEqual(await api('horariosOferta',admin,offer2),horariosAntes);
console.log('OK cambio de horario rechazado si cruza materias del mismo estudiante');
assert.ok((await api('bloquesConfirmados',student2)).some(row => row[0]==='DEMO-SI-002'));
assert.ok((await api('matriculas',teacher,offer1)).length);
assert.deepEqual(await api('matriculas',teacher,offer2),[]);
await pdf(teacher,'tipo=asistencia&materia='+offer1+'&paralelo=B');
await pdf(student2,'tipo=asistencia&materia='+offer1);
await pdf(admin,'tipo=asistencia&general=true');
await api('retirarFaceId',student2);
assert.equal((await api('estadoFacial',student2))[0][0],'SIN_REGISTRAR');
await reject(/prueba facial/i,'marcar',student2,'1','prueba-inexistente');
const periodo = (await api('semestres',admin)).find(p=>p[0]===period);
await api('guardarSemestre',admin,period,...periodo.slice(1,6),'MATRICULA_CERRADA');
await reject(/plazo/i,'confirmarSeleccion',student3,period,'matricula-v1',offer1,'B');
assert.deepEqual(await api('ofertasDisponibles',student3),[]);
await api('excepcionMatricula',admin,period,demoStudent[0],periodo[4],'Excepción DEMO de integración');
assert.ok((await api('ofertasDisponibles',student3)).some(o=>o[0]===offer1));
await api('excepcionMatricula',admin,period,demoStudent[0],'','');
await reject(/plazo/i,'confirmarSeleccion',student3,period,'matricula-v1',offer1,'B');
console.log('OK cierre de plazo, excepción individual y revocación');
console.log('OK competencia por último cupo, cruce semanal, legalización manual y automática, horario, PDF y retiro de plantilla');


const inicioPeriodo = periodo[2];
const diaPeriodo = offset => {
  const dia = new Date(inicioPeriodo + 'T00:00:00Z');
  dia.setUTCDate(dia.getUTCDate() + offset);
  return dia.toISOString().slice(0,10);
};
const parcialesAnteriores = await api('parciales',admin,offer1);
await reject(/permiso/i,'guardarParcialPeriodo',teacher,period,'Primer parcial',inicioPeriodo,diaPeriodo(9));
await api('guardarParcialPeriodo',admin,period,'Primer parcial',inicioPeriodo,diaPeriodo(9));
await reject(/antes/i,'guardarParcialPeriodo',admin,period,'Segundo parcial',diaPeriodo(9),diaPeriodo(19));
await api('guardarParcialPeriodo',admin,period,'Segundo parcial',diaPeriodo(10),diaPeriodo(19));
const comunes = await api('parcialesPeriodo',admin,period);
assert.deepEqual(comunes,[['Primer parcial',inicioPeriodo,diaPeriodo(9)],['Segundo parcial',diaPeriodo(10),diaPeriodo(19)]]);
const porOferta = async id => (await api('parciales',admin,id)).filter(r=>comunes.some(p=>p[0]===r[1]));
const originales = await porOferta(offer1);
assert.deepEqual(originales.map(r=>r.slice(1)),comunes);
assert.deepEqual((await porOferta(offer2)).map(r=>r.slice(1)),comunes);
assert.ok((await api('parciales',admin,offer1)).some(r=>r[0]===parcialesAnteriores[0][0]));
await api('guardarCatalogo',admin,'',demoStudent[9],'DEMO-PARC','DEMO · Parciales comunes','1','3','true');
const catalogo = (await api('catalogo',admin,demoStudent[9])).find(r=>r[1]==='DEMO-PARC');
await api('guardarMateria',admin,'','','',teacherId,'',period,catalogo[0],'true');
const nueva = (await api('materias',admin)).find(r=>r[1]==='DEMO-PARC');
assert.deepEqual((await porOferta(nueva[0])).map(r=>r.slice(1)),comunes);
await api('guardarParcialPeriodo',admin,period,'Segundo parcial',diaPeriodo(10),diaPeriodo(20));
assert.equal((await porOferta(nueva[0]))[1][3],diaPeriodo(20));
assert.equal((await porOferta(offer1))[1][0],originales[1][0]);
console.log('OK parciales del período, herencia en ofertas existentes y nuevas, fechas e IDs conservados');

const [[parcialNuevo]] = await api('parciales',admin,nueva[0]);
const horariosAgendados = () => api('clases',teacher,parcialNuevo);
assert.deepEqual(await horariosAgendados(),[]);
await api('guardarHorarioOferta',admin,'',nueva[0],'A','5','10:00','11:00','LAB PARCIALES','true');
const generadas = await horariosAgendados();
assert.ok(generadas.length >= 1);
assert.ok(generadas.every(r=>r[1]==='Por definir' && r[2].endsWith('10:00') && r[3].endsWith('11:00')
  && r[6]==='A' && r[7]==='LAB PARCIALES' && r[8]==='HORARIO'));
const [[otroDocente]] = await api('ingresarDocente','','demo.docente2',secret);
await reject(/oferta asignada/i,'guardarTemaClase',otroDocente,generadas[0][0],'Tema ajeno');
await reject(/permiso/i,'guardarClase',teacher,'',parcialNuevo,'Clase manual',generadas[0][2],generadas[0][3],'5','A','LAB PARCIALES');
await reject(/permiso/i,'cancelar',teacher,generadas[0][0]);
await api('guardarTemaClase',teacher,generadas[0][0],'Prueba Face ID');
const [[bloque]] = await api('horariosOferta',admin,nueva[0]);
await api('guardarHorarioOferta',admin,bloque,nueva[0],'A','5','10:00','11:30','LAB PARCIALES','true');
let sesionesClases = await horariosAgendados();
assert.deepEqual(sesionesClases.map(r=>r[0]),generadas.map(r=>r[0]));
assert.equal(sesionesClases[0][1],'Prueba Face ID');
assert.ok(sesionesClases[0][3].endsWith('11:30'));
await api('guardarHorarioOferta',admin,bloque,nueva[0],'A','5','10:00','11:30','LAB PARCIALES','false');
sesionesClases = await horariosAgendados();
assert.ok(sesionesClases.every(r=>r[5]==='Cancelada'));
console.log('OK sesiones automáticas, permisos, tema, actualización del horario y cancelación');

await api('guardarHorarioOferta',admin,bloque,nueva[0],'A','5','10:00','11:30','LAB PARCIALES','true');
const parcialesDelHorario = await api('parciales',teacher,nueva[0]);
const fuera = parcialesDelHorario.find(r=>r[1]==='Fuera de parciales');
assert.ok(fuera);
const sesionesVigentes = (await Promise.all(parcialesDelHorario.map(r=>api('clases',teacher,r[0]))))
  .flat().filter(r=>r[5]==='Activa').map(r=>r[2].slice(0,10)).sort();
const viernesEsperados = [];
for (let offset=0; diaPeriodo(offset)<=periodo[3]; offset++) {
  const dia = diaPeriodo(offset);
  if (new Date(dia+'T00:00:00Z').getUTCDay()===5) viernesEsperados.push(dia);
}
assert.deepEqual(sesionesVigentes,viernesEsperados);
const aMover = (await api('clases',teacher,fuera[0])).find(r=>r[5]==='Activa' && r[2].slice(0,10)>diaPeriodo(20));
assert.ok(aMover);
await api('guardarTemaClase',teacher,aMover[0],'Tema preservado al asignar parcial');
await api('guardarParcialPeriodo',admin,period,'Segundo parcial',diaPeriodo(10),aMover[2].slice(0,10));
const segundoParcial = parcialesDelHorario.find(r=>r[1]==='Segundo parcial');
const reclasificada = (await api('clases',teacher,segundoParcial[0])).find(r=>r[0]===aMover[0]);
assert.equal(reclasificada?.[1],'Tema preservado al asignar parcial');
assert.equal(reclasificada?.[5],'Activa');
assert.ok(!(await api('clases',teacher,fuera[0])).some(r=>r[0]===aMover[0]));
console.log('OK recurrencia semanal hasta el fin del período y reclasificación sin perder el tema');

const hoyClase = diaPeriodo(-1);
await api('guardarSemestre',admin,'','DEMO · Clase inmediata',hoyClase,hoyClase,'','','EN_CURSO');
const inmediato = (await api('semestres',admin)).find(r=>r[1]==='DEMO · Clase inmediata')[0];
await api('guardarMateria',admin,'','','',teacherId,'',inmediato,catalogo[0],'true');
const ofertaInmediata = (await api('materias',admin)).find(r=>r[1]==='DEMO-PARC' && r[6]===inmediato)[0];
await reject(/oferta activa asignada/i,'iniciarClaseAhora',otroDocente,ofertaInmediata,'A','Tema ajeno');
await api('iniciarClaseAhora',teacher,ofertaInmediata,'A','Prueba de asistencia en vivo');
const [[rangoInmediato]] = await api('parciales',teacher,ofertaInmediata);
const [sesionInmediata] = await api('clases',teacher,rangoInmediato);
assert.equal(sesionInmediata[1],'Prueba de asistencia en vivo');
assert.equal(sesionInmediata[6],'A');
assert.equal(sesionInmediata[8],'MANUAL');
assert.equal(new Date(sesionInmediata[3].replace(' ','T')+'Z')-new Date(sesionInmediata[2].replace(' ','T')+'Z'),30*60*1000);
await api('iniciarClaseAhora',teacher,ofertaInmediata,'A','Tema actualizado');
const repetida = await api('clases',teacher,rangoInmediato);
assert.equal(repetida.length,1);
assert.equal(repetida[0][0],sesionInmediata[0]);
assert.equal(repetida[0][1],'Tema actualizado');
console.log('OK inicio inmediato, permisos, duración y reintento sin duplicados');
