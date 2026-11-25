'use strict';
const $ = (s, root=document) => root.querySelector(s);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const state = {token:'', role:'', name:'', tab:'', materias:[], cuentas:[], semestres:[], inscripciones:[], parciales:[], clases:[], detalle:[], mallas:[], catalogo:[], selectedMalla:'', selectedMateria:'', selectedParcial:''};
const TERMS_VERSION = 'matricula-v1';
const ROLE = {ADMIN:'Administración',DOCENTE:'Docente',ESTUDIANTE:'Estudiante'};
const NAV = {ADMIN:[['inicio','⌂','Inicio'],['cuentas','♙','Estudiantes y docentes'],['malla','▤','Malla curricular'],['periodos','▦','Períodos'],['materias','▧','Ofertas académicas'],['parciales','▥','Parciales'],['clases','◷','Clases'],['matriculas','✓','Matrículas'],['justificaciones','!','Justificaciones'],['reportes','▧','Reportes'],['auditoria','▤','Auditoría']],DOCENTE:[['inicio','⌂','Inicio'],['materias','▤','Mis materias'],['clases','◷','Clases'],['justificaciones','!','Justificaciones'],['reportes','▧','Reportes']],ESTUDIANTE:[['inicio','⌂','Inicio'],['perfil','♙','Mi perfil'],['matricula','▤','Mi matrícula'],['asistencia','✓','Asistencia'],['historial','▥','Historial académico'],['justificaciones','!','Justificaciones'],['faceid','◎','Face ID'],['horario','▦','Mi horario']]};
const decoder = new TextDecoder(); const encoder = new TextEncoder();
function encode(rows){const chunks=[];let total=0;const addInt=n=>{const b=new Uint8Array(4);new DataView(b.buffer).setInt32(0,n,false);chunks.push(b);total+=4};addInt(rows.length);for(const row of rows){addInt(row.length);for(const v of row){const b=encoder.encode(String(v??''));addInt(b.length);chunks.push(b);total+=b.length}}const output=new Uint8Array(total);let at=0;for(const b of chunks){output.set(b,at);at+=b.length}return output}
function decode(buffer){const d=new DataView(buffer);let pos=0;function int(){if(pos+4>d.byteLength)throw Error('Respuesta incompleta.');const n=d.getInt32(pos,false);pos+=4;return n}const n=int();if(n<0||n>50000)throw Error('Respuesta inválida.');const rows=[];for(let i=0;i<n;i++){const count=int();if(count<0||count>32)throw Error('Respuesta inválida.');const row=[];for(let j=0;j<count;j++){const size=int();if(size<0||pos+size>d.byteLength)throw Error('Respuesta inválida.');row.push(decoder.decode(new Uint8Array(buffer,pos,size)));pos+=size}rows.push(row)}return rows}
async function api(action,...fields) {
  const controller = new AbortController();
  const timeout = setTimeout(()=>controller.abort(),45000);
  try {
    let response, bytes;
    try {
      response = await fetch('/asistencia',{
        method:'POST', headers:{'Content-Type':'application/octet-stream'},
        body:encode([[action,state.token,...fields]]), signal:controller.signal
      });
      bytes = await response.arrayBuffer();
    } catch (error) {
      if (error.name==='AbortError') throw Error('El servidor tardó demasiado. Si estabas guardando, revisa el resultado antes de volver a intentarlo.');
      throw Error('No se pudo conectar con el servidor. Comprueba la conexión y vuelve a intentarlo.');
    }
    let rows;
    try { rows = decode(bytes); }
    catch { throw Error('El servidor devolvió una respuesta inválida. Recarga la pantalla y vuelve a intentarlo.'); }
    if (!response.ok) throw Error(rows[0]?.[0]||'No se pudo completar la solicitud.');
    return rows;
  } finally {
    clearTimeout(timeout);
  }
}
async function downloadPdf(query){const response=await fetch('/api/reportes?'+query,{headers:{'X-Asistencia-Sesion':state.token}});if(!response.ok)throw Error(await response.text()||'No se pudo generar el PDF.');const blob=await response.blob();const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=response.headers.get('Content-Disposition')?.match(/filename="?([^";]+)"?/i)?.[1]||'reporte.pdf';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
function enlaceDocumento(r,id){return `<a href="data:${esc(r[0])};base64,${esc(r[1])}" target="_blank" rel="noopener" download="justificacion-${esc(id)}">Abrir o descargar documento</a>`}
async function safe(action,...fields){try{return await api(action,...fields)}catch(e){toast(e.message,true);return null}}
function toast(msg,bad=false){const node=$('#toast');node.textContent=msg;node.classList.toggle('bad',bad);node.hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>node.hidden=true,4700)}
function title(text,sub='',button=''){return `<div class="page-heading"><div><h1>${esc(text)}</h1><p>${esc(sub)}</p></div>${button}</div>`}
function section(text,html){return `<section class="section"><h2>${esc(text)}</h2>${html}</section>`}
function table(headers,rows,action){if(!rows?.length)return '<div class="empty">No hay registros para mostrar.</div>';return `<div class="table-wrap"><table class="data-table"><thead><tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}${action?'<th>Acciones</th>':''}</tr></thead><tbody>${rows.map((r,i)=>`<tr>${headers.map((_,j)=>`<td>${formatCell(r[j])}</td>`).join('')}${action?`<td><div class="actions">${action(r,i)||''}</div></td>`:''}</tr>`).join('')}</tbody></table></div>`}
function formatCell(v){const text=String(v??'');if(['APROBADA','ASISTENCIA','REGISTRADO','ABIERTA','A tiempo','Activa'].includes(text))return `<span class="pill good">${esc(text)}</span>`;if(['RECHAZADA','RETIRADA','Cancelada','Falta sin justificar'].includes(text))return `<span class="pill bad">${esc(text)}</span>`;if(['PENDIENTE','RESERVADA','HABILITADA','ATRASO','Atraso','FALTA FOTO'].includes(text))return `<span class="pill warn">${esc(text)}</span>`;return esc(text)}
const action=(label,cmd,i,variant='soft')=>`<button class="${variant} table-action" data-cmd="${esc(cmd)}" data-i="${i}">${esc(label)}</button>`;
function setMain(html){$('#main').innerHTML=html;$('#current-title').textContent=NAV[state.role]?.find(n=>n[0]===state.tab)?.[2]||'Inicio'}
function buttons(){document.querySelectorAll('#main [data-cmd]').forEach(b=>b.onclick=()=>dispatch(b.dataset.cmd,Number(b.dataset.i),b))}
function options(rows,labelIndex=1,placeholder='Seleccionar'){return `<option value="">${esc(placeholder)}</option>`+rows.map(r=>`<option value="${esc(r[0])}">${esc(r[labelIndex])}</option>`).join('')}
function select(id,rows,label='Selecciona',idx=1){return `<select id="${esc(id)}">${options(rows,idx,label)}</select>`}
function btn(label,cmd,variant='primary'){return `<button class="${variant}" data-cmd="${cmd}">${esc(label)}</button>`}
function dateNow(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`}
function modal(titleText,content,onSubmit){$('#modal-title').textContent=titleText;$('#modal-body').innerHTML=content;const dialog=$('#modal');dialog.oncancel=null;$('#modal-close').onclick=()=>dialog.close();dialog.showModal();const form=$('#modal-body form');if(form)form.addEventListener('submit',async e=>{e.preventDefault();const submit=$('button[type="submit"]',form);submit.disabled=true;const errorBox=$('.modal-error',form);errorBox?.remove();try{await onSubmit(new FormData(form));dialog.close()}catch(error){const notice=document.createElement('p');notice.className='error modal-error';notice.setAttribute('role','alert');notice.textContent=error.message;form.prepend(notice)}finally{submit.disabled=false}})}
function fields(defs){return `<form><div class="form-grid">${defs.map(f=>`<label class="${f.full?'full':''}">${esc(f.label)}${f.type==='select'?`<select name="${esc(f.name)}" ${f.required?'required':''} ${f.disabled?'disabled':''}>${(f.options||[]).map(o=>`<option value="${esc(o[0])}" ${String(o[0])===String(f.value??'')?'selected':''}>${esc(o[1])}</option>`).join('')}</select>`:`<input name="${esc(f.name)}" type="${esc(f.type||'text')}" ${f.type==='file'&&f.accept?'accept="'+esc(f.accept)+'"':''} ${f.type==='file'?'':'value="'+esc(f.value??'')+'"'} ${f.required?'required':''} ${f.disabled?'disabled':''} ${f.min?'min="'+esc(f.min)+'"':''} ${f.max?'max="'+esc(f.max)+'"':''} ${f.placeholder?'placeholder="'+esc(f.placeholder)+'"':''}>`}</label>`).join('')}</div><button class="primary wide" type="submit">Guardar cambios <span>✓</span></button></form>`}
function formValue(data,name){return String(data.get(name)??'').trim()}
async function formFileBase64(data,name){const file=data.get(name);if(!file||!file.size)return'';if(file.size>1_500_000)throw Error('El archivo debe tener hasta 1.5 MB.');const bytes=new Uint8Array(await file.arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(binary)}
async function perform(actionName,...args){const rows=await api(actionName,...args);toast(rows[0]?.[0]||'Guardado correctamente.');await render();return rows}
async function login(e){e.preventDefault();$('#auth-error').hidden=true;const btn=$('#login-form button[type="submit"]');btn.disabled=true;try{const rows=await api($('#login-type').value,$('#login-user').value.trim(),$('#login-password').value);state.token=rows[0][0];state.role=rows[0][1];state.name=rows[0][2];$('#login-password').value='';$('#auth').hidden=true;$('#shell').hidden=false;$('#user-name').textContent=state.name;$('#user-role').textContent=ROLE[state.role]||state.role;$('#avatar').textContent=state.name.slice(0,1).toUpperCase();$('#nav').innerHTML=NAV[state.role].map(n=>`<button class="nav-item" data-tab="${n[0]}"><span>${n[1]}</span>${n[2]}</button>`).join('');document.querySelectorAll('[data-tab]').forEach(n=>n.addEventListener('click',()=>navigate(n.dataset.tab)));await navigate('inicio')}catch(error){$('#auth-error').textContent=error.message;$('#auth-error').hidden=false}finally{btn.disabled=false}}
async function register(e){e.preventDefault();const btn=$('#register-form button[type="submit"]');btn.disabled=true;try{await api('registrar',$('#reg-user').value.trim(),$('#reg-name').value.trim(),$('#reg-last').value.trim(),$('#reg-pass').value,'',$('#reg-email').value.trim());toast('Cuenta creada. Pide a Administración que asigne tu usuario institucional UTA.');$('#login-type').value='ingresarEstudianteInstitucional';actualizarAcceso();$('#login-user').value='';switchRegister(false)}catch(error){$('#auth-error').textContent=error.message;$('#auth-error').hidden=false}finally{btn.disabled=false}}
function actualizarAcceso() {
  const admin = $('#login-type').value === 'ingresarAdmin';
  const usuario = $('#login-user');
  usuario.type = admin ? 'text' : 'email';
  usuario.placeholder = admin ? 'Usuario de administración' : 'nombre@uta.edu.ec';
  $('#login-user-label').textContent = admin ? 'Usuario de administración' : 'Correo institucional';
  $('#auth-subtitle').textContent = admin ? 'Ingresa con tu usuario de administración y contraseña.' : 'Ingresa con tu correo institucional y contraseña.';
}
$('#login-type').addEventListener('change', actualizarAcceso);

function switchRegister(open){$('#register-form').hidden=!open;$('#login-form').hidden=open;$('#switch-row').hidden=open;$('#auth-title').textContent=open?'Crear cuenta estudiantil':'Bienvenido';$('#auth-subtitle').textContent=open?'Completa tus datos para registrarte.':'Ingresa con tu correo institucional y contraseña.';$('#auth-error').hidden=true;if(!open)actualizarAcceso();if(open){const back=document.createElement('button');back.textContent='← Volver al inicio de sesión';back.className='link-btn';back.type='button';back.id='back-register';back.onclick=()=>switchRegister(false);$('#register-form').append(back)}else{$('#back-register')?.remove()}}
async function navigate(tab){state.tab=tab;document.querySelectorAll('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));$('.sidebar').classList.remove('open');setMain('<div class="section">Cargando información…</div>');try{await render()}catch(error){setMain(section('No se pudo cargar',`<div class="error">${esc(error.message)}</div>${btn('Reintentar','refresh')}`));buttons()}}
async function render(){if(state.role==='ESTUDIANTE')return renderStudent();return renderManagement()}
function filteredTable(headers, rows, present, actions, filters, id = "filtered-results") {
  const controls = filters.map(({label, choices, type = "search"}) => choices
    ? '<label>'+esc(label)+'<select data-table-filter><option value="">Todos</option>'+choices.map(([value,text])=>'<option value="'+esc(value)+'">'+esc(text)+'</option>').join('')+'</select></label>'
    : '<label>'+esc(label)+'<input type="'+esc(type)+'" data-table-filter></label>');
  const html = '<div class="row">'+controls.join('')+'</div><div id="'+esc(id)+'"></div>';
  const normalize = value => String(value??'').normalize('NFD').replace(/\p{M}/gu,'').toLowerCase();
  function mount() {
    const results = $('#'+id);
    const inputs = [...results.parentElement.querySelectorAll('[data-table-filter]')];
    function update() {
      const selected = inputs.map(input=>input.value.trim());
      const matches = rows.map((row,index)=>({row,index})).filter(({row})=>filters.every(({get,choices},i)=>{
        const value = selected[i];
        return !value || (choices ? String(get(row))===value : normalize(get(row)).includes(normalize(value)));
      }));
      results.innerHTML = matches.length
        ? table(headers,matches.map(({row})=>present(row)),actions ? (row,i)=>actions(row,matches[i].index) : null)
        : '<div class="empty">No hay registros que coincidan.</div>';
      buttons();
    }
    inputs.forEach(input=>input.addEventListener(input.tagName==='SELECT'?'change':'input', update));
    update();
  }
  return {html,mount};
}

function filterChoices(rows, index) {
  return [...new Set(rows.map(row=>row[index]).filter(Boolean))].sort().map(value=>[value,value]);
}

async function renderManagement(){if(state.tab==='inicio'){const materias=await api('materias');const periodos=await api('semestres');state.materias=materias;state.semestres=periodos;let count=0;if(state.role==='ADMIN'){state.cuentas=await api('cuentas');count=state.cuentas.length}setMain(title(`Hola, ${state.name.split(' ')[0]}`,'Panel de control del sistema académico')+`<div class="grid"><div class="stat"><span class="symbol">▤</span><strong>${materias.length}</strong><small>Materias</small></div><div class="stat"><span class="symbol">▦</span><strong>${periodos.length}</strong><small>Períodos académicos</small></div>${state.role==='ADMIN'?`<div class="stat"><span class="symbol">♙</span><strong>${count}</strong><small>Cuentas registradas</small></div>`:''}</div>`+section('Accesos rápidos',`<div class="actions">${btn('Ver materias','goto-materias')}${btn('Gestionar clases','goto-clases','soft')}${btn('Reportes','goto-reportes','soft')}</div>`));buttons();return}
if(state.tab==='justificaciones'){state.solicitudes=await api('solicitudesJustificacion');setMain(title('Justificaciones','Revisa solicitudes pendientes de las materias asignadas')+section('Solicitudes pendientes',table(['Estudiante','Código','Materia','Clase','Motivo','Solicitada'],state.solicitudes.map(r=>r.slice(1,7)),(r,i)=>action('Aprobar','approve-justification',i)+action('Rechazar','reject-justification',i,'danger')+(r[8]==='Sí'?action('Ver adjunto','view-justification-document',i):''))));buttons();return}
if (state.tab==='malla') {
  state.mallas = await api('mallas');
  if (!state.mallas.some(r=>r[0]===state.selectedMalla)) {
    state.selectedMalla = state.mallas.find(r=>r[5]==='ACTIVA')?.[0] || state.mallas[0]?.[0] || '';
  }
  state.catalogo = state.selectedMalla ? await api('catalogo',state.selectedMalla) : [];
  const versiones = filteredTable(['Malla','Versión','Vigente desde','Vigente hasta','Estado','Demo'], state.mallas,
    r=>r.slice(1,7), (_,i)=>action('Editar','edit-malla',i), [
      {label:'Buscar por nombre o versión', get:r=>r.slice(1,3).join(' ')},
      {label:'Estado de la malla', get:r=>r[5], choices:filterChoices(state.mallas,5)}
    ], 'malla-resultados');
  const asignaturas = filteredTable(['Código','Asignatura','Nivel','Créditos','Estado'], state.catalogo,
    r=>r.slice(1,6), (_,i)=>action('Editar','edit-catalog',i)+action('Prerrequisitos','edit-prerequisites',i,'soft'), [
      {label:'Buscar por código o asignatura', get:r=>r.slice(1,3).join(' ')},
      {label:'Nivel', get:r=>r[3], choices:filterChoices(state.catalogo,3).sort((a,b)=>Number(a[0])-Number(b[0]))},
      {label:'Estado de la asignatura', get:r=>r[5], choices:filterChoices(state.catalogo,5)}
    ], 'catalogo-resultados');
  const selector = '<div class="row"><label>Malla del catálogo'+select('malla-select',state.mallas,'Selecciona una malla',1)+'</label></div>';
  setMain(title('Malla curricular','Catálogo configurable. Ingresa únicamente información institucional verificada.',
      btn('+ Nueva malla','new-malla')+btn('Reglas académicas','edit-academic-config','soft'))
    +section('Versiones de malla',versiones.html)
    +section('Asignaturas del catálogo',selector+'<div class="actions spaced">'+btn('+ Nueva asignatura','new-catalog')+'</div>'+asignaturas.html));
  const malla = $('#malla-select');
  malla.value = state.selectedMalla;
  malla.addEventListener('change',async()=>{
    state.selectedMalla = malla.value;
    await renderManagement();
  });
  versiones.mount();
  asignaturas.mount();
  return;
}
if(state.tab==='cuentas'){
  const rows = state.cuentas = await api('cuentas');
  const correo = r=>r[12]?r[12]+'@uta.edu.ec':'';
  const listado = filteredTable(['Rol','Cédula','Nombre completo','Correo institucional','Estado'], rows,
    r=>[ROLE[r[1]],/^[0-9]{10}$/.test(r[2])?r[2]:'',r[3],correo(r),r[11]],
    (_,i)=>action('Editar','edit-account',i), [
      {label:'Buscar por nombre, cédula o correo', get:r=>[r[3],r[2],correo(r),r[7]].join(' ')},
      {label:'Rol', get:r=>r[1], choices:[['ESTUDIANTE','Estudiante'],['DOCENTE','Docente']]},
      {label:'Estado', get:r=>r[11], choices:filterChoices(rows,11)}
    ]);
  setMain(title('Cuentas de usuario','Administra docentes y estudiantes',btn('+ Nueva cuenta','new-account'))+section('Usuarios registrados',listado.html));
  listado.mount();
  return;
}
if (state.tab==='periodos') {
  const rows = state.semestres = await api('semestres');
  const listado = filteredTable(['Nombre','Desde','Hasta','Matrícula desde','Matrícula hasta','Estado'], rows,
    r=>r.slice(1,7), (_,i)=>rows[i][0]!=='0'
      ? action('Editar','edit-period',i)+action('Excepción de plazo','enrollment-exception',i) : '', [
      {label:'Buscar por nombre', get:r=>r[1]},
      {label:'Estado', get:r=>r[6], choices:filterChoices(rows,6)}
    ]);
  setMain(title('Períodos académicos','Define fechas del semestre y su ventana de matrícula',btn('+ Nuevo período','new-period'))
    +section('Períodos',listado.html));
  listado.mount();
  return;
}
if(state.tab==='materias'){
  const rows = state.materias = await api('materias');
  const add = state.role==='ADMIN'?btn('+ Nueva oferta','new-subject'):'';
  const listado = filteredTable(['Código','Materia','Docente','Nivel','Período','Estado'], rows,
    r=>[r[1],r[2],r[3],r[5],r[7],r[9]], (_,i)=>action('Horarios','offer-schedule',i)
      +(state.role==='ADMIN'?action('Editar','edit-subject',i):action('Estudiantes','teacher-roster',i)), [
      {label:'Buscar por código, asignatura o docente', get:r=>r.slice(1,4).join(' ')},
      {label:'Período', get:r=>r[7], choices:filterChoices(rows,7)},
      {label:'Estado', get:r=>r[9], choices:filterChoices(rows,9)}
    ]);
  setMain(title('Materias y ofertas','Catálogo por período con docente asignado',add)+section('Ofertas académicas',listado.html));
  listado.mount();
  return;
}
if (state.tab==='parciales') {
  const periodos = (await api('semestres')).filter(r=>r[0]!=='0');
  const seleccionado = periodos.find(r=>r[0]===state.periodoParciales);
  const periodo = seleccionado?.[0] || '';
  state.periodoParcialesDatos = seleccionado;
  state.parcialesPeriodo = periodo ? await api('parcialesPeriodo',periodo) : [];
  const seleccion = '<div class="row"><label>Período académico'+select('periodo-parciales',periodos,'Selecciona un período',1)+'</label></div>';
  const lista = periodo
    ? section('Calendario de parciales', '<p class="muted">Fechas del período: '+esc(seleccionado[2].split('-').reverse().join('/'))+' al '+esc(seleccionado[3].split('-').reverse().join('/'))+'.</p>'
        +(state.parcialesPeriodo.length<2 ? '<div class="actions spaced">'+btn('+ Agregar parcial','new-partial')+'</div>' : '')
        +table(['Parcial','Desde','Hasta'],state.parcialesPeriodo,(_,i)=>action('Editar','edit-period-partial',i)))
    : '';
  setMain(title('Parciales','Configura las fechas que comparten todas las ofertas de un período')
    +section('Período',seleccion)+lista);
  const selector = $('#periodo-parciales');
  selector.value = periodo;
  selector.addEventListener('change',()=>{
    state.periodoParciales = selector.value;
    void navigate('parciales');
  });
  buttons();
  return;
}
if (state.tab==='clases') {
  state.materias = await api('materias');
  if (!state.materias.some(r=>r[0]===state.selectedMateria)) {
    state.selectedMateria = state.materias.length===1 ? state.materias[0][0] : '';
    state.selectedParcial = '';
  }
  const lista = state.materias.map(r=>[r[0],r[2]+' · '+r[7]]);
  const selector = '<div class="row"><label>Materia'+select('subject-filter',lista,'Selecciona una materia')
    +'</label><label>Parcial<select id="partial-filter"><option value="">Todos los parciales</option></select></label></div>';
  setMain(title('Clases programadas','Consulta las sesiones del horario de la materia')+section('Filtros',selector)+'<div id="content-area"></div>');
  const materia = $('#subject-filter');
  const parcial = $('#partial-filter');
  materia.value = state.selectedMateria;
  materia.addEventListener('change',()=>{
    state.selectedMateria = materia.value;
    state.selectedParcial = '';
    loadPartialFilters().catch(error=>toast(error.message,true));
  });
  parcial.addEventListener('change',()=>{
    state.selectedParcial = parcial.value;
    showPartialContent().catch(error=>toast(error.message,true));
  });
  await loadPartialFilters();
  return;
}
if(state.tab==='matriculas'){state.materias=await api('materias');setMain(title('Gestión de matrículas','Habilita, legaliza o retira matrículas')+section('Selecciona una materia',`<div class="row"><label>Materia${select('enroll-subject',state.materias,'Selecciona una materia',2)}</label></div>`)+`<div id="content-area"></div>`);$('#enroll-subject').value=state.selectedMateria||'';$('#enroll-subject').addEventListener('change',()=>{state.selectedMateria=$('#enroll-subject').value;loadEnrollments().catch(error=>toast(error.message,true))});await loadEnrollments();return}
if (state.tab==='auditoria') {
  const rows = await api('auditoria');
  const listado = filteredTable(['Fecha','Usuario','Operación','Referencia'], rows, r=>r, null, [
    {label:'Buscar por usuario o referencia', get:r=>[r[1],r[3]].join(' ')},
    {label:'Operación', get:r=>r[2], choices:filterChoices(rows,2)},
    {label:'Fecha', type:'date', get:r=>r[0].slice(0,10)}
  ]);
  setMain(title('Auditoría','Últimos 100 cambios')+section('Cambios registrados',listado.html));
  listado.mount();
  return;
}
if(state.tab==='reportes'){
  state.materias=await api('materias');
  const filtros='<div class="row"><label>Materia'+select('report-subject',state.materias,'Selecciona materia',2)
    +'</label><label>Parcial<select id="report-partial"><option value="">Todos</option></select></label>'
    +'<label>Paralelo<select id="report-group"><option value="">Todos</option></select></label></div>';
  setMain(title('Reportes de asistencia','Consulta por materia, paralelo o parcial')
    +section('Filtros',filtros+'<div class="actions spaced">'+btn('Descargar PDF','download-report','soft')
      +(state.role==='ADMIN'?btn('Descargar reporte general','download-general','outline'):'')+'</div>')
    +'<div id="content-area"></div>');
  $('#report-subject').addEventListener('change',async()=>{
    try{
      const id=$('#report-subject').value;
      const [parciales,grupos]=id?await Promise.all([api('parciales',id),api('paralelos',id)]):[[],[]];
      if($('#report-subject')?.value!==id)return;
      $('#report-partial').innerHTML=options(parciales,1,'Todos');
      $('#report-group').innerHTML=options(grupos,0,'Todos');
      await loadReport();
    }catch(e){toast(e.message,true)}
  });
  $('#report-partial').addEventListener('change',()=>loadReport().catch(e=>toast(e.message,true)));
  buttons();return;
}}
async function loadPartialFilters() {
  const subject = state.selectedMateria;
  const selector = $('#partial-filter');
  selector.disabled = true;
  const parciales = subject ? await api('parciales',subject) : [];
  if (state.selectedMateria!==subject || $('#partial-filter')!==selector) return;
  state.parciales = parciales;
  if (!parciales.some(r=>r[0]===state.selectedParcial)) state.selectedParcial = '';
  selector.innerHTML = options(parciales,1,'Todos los parciales');
  selector.value = state.selectedParcial;
  selector.disabled = !parciales.length;
  await showPartialContent();
}
async function showPartialContent() {
  const target = $('#content-area');
  if (!target) return;
  const {selectedMateria: materia, selectedParcial: parcial} = state;
  if (!materia) {
    target.innerHTML = section('Clases','<div class="empty">Selecciona una materia.</div>');
    return;
  }
  const rangos = state.parciales.filter(r=>!parcial||r[0]===parcial);
  const resultados = await Promise.all(rangos.map(async ([id,nombre])=>
    (await api('clases',id)).map(clase=>[...clase,nombre])));
  if (state.selectedMateria!==materia || state.selectedParcial!==parcial || $('#content-area')!==target) return;
  const clases = resultados.flat().sort((a,b)=>a[2].localeCompare(b[2]));
  state.clases = clases;
  const aviso = parcial
    ? 'No hay sesiones en este parcial. Selecciona Todos los parciales para ver el horario completo.'
    : 'No hay sesiones programadas. Administración configura las sesiones desde el horario semanal de la oferta.';
  target.innerHTML = section('Sesiones programadas','<div class="actions spaced">'+btn('Iniciar clase ahora','start-class-now')+'</div>'+(clases.length
    ? table(['Tema','Inicio','Fin','Estado','Paralelo','Aula','Parcial'],clases.map(r=>[...r.slice(1,4),...r.slice(5,8),r[10]]),
        (_,i)=>clases[i][9]==='SI'?action('Editar tema','edit-class-topic',i):'')
    : '<div class="empty">'+esc(aviso)+'</div>'));
  buttons();
}
async function loadEnrollments(){const target=$('#content-area');const materia=state.selectedMateria;if(!target)return;if(!materia){target.innerHTML=section('Matrículas','<div class="empty">Selecciona una materia.</div>');return}const [rows,cupos]=await Promise.all([api('matriculas',materia),api('cupos',materia)]);if(state.selectedMateria!==materia||$('#content-area')!==target)return;state.enrollments=rows;const listado=filteredTable(['Cédula','Estudiante','Estado','Foto','Desde','Hasta','Paralelo'],rows,r=>r.slice(1,8),(r,i)=>action('Legalizar','legalize',i)+action('Rechazar','reject',i,'danger')+action('Retirar','withdraw',i,'outline')+(r[4]?action('Ver foto','view-enrollment-photo',i):'')+(['APROBADA','RETIRADA'].includes(r[2])?action('Resultado académico','record-result',i):''),[{label:'Buscar por nombre o cédula',get:r=>r.slice(1,3).join(' ')},{label:'Estado',get:r=>r[3],choices:filterChoices(rows,3)},{label:'Paralelo',get:r=>r[7],choices:filterChoices(rows,7)}]);target.innerHTML=section('Cupos disponibles',`<div class="actions">${cupos.map(r=>`<span class="pill">Paralelo ${esc(r[0])}: ${esc(r[2])} / ${esc(r[1])}</span>`).join('')}${btn('Configurar cupos','edit-cupos','soft')}</div>`)+section('Estudiantes matriculados',`<div class="actions spaced">${btn('+ Habilitar estudiante','enable-student')}</div>`+listado.html);listado.mount()}
async function loadReport(){const id=$('#report-partial').value;if(!id){$('#content-area').innerHTML='';return}const rows=await api('detalle',id);state.detalle=rows;$('#content-area').innerHTML=section('Detalle de asistencia',``+table(['Clase','Estudiante','Estado','Motivo','Fecha'],rows.map(r=>[r[4]||r[0],r[3]||r[1],r[7]||'',r[9]||'',r[5]||'']),(r,i)=>['Programada','En curso sin marca'].includes(state.detalle[i][7])?'':action('Corregir','correct-attendance',i)+(r[2]==='Falta sin justificar'?action('Justificar','justify',i):'')));buttons()}
async function renderStudent(){if(state.tab==='inicio'){const rows=await api('misMaterias');state.inscripciones=rows;const face=(await api('estadoFacial'))[0]?.[0];setMain(title(`Hola, ${state.name.split(' ')[0]}`,'Bienvenido a tu portal estudiantil')+`<div class="grid"><div class="stat"><span class="symbol">▤</span><strong>${rows.length}</strong><small>Materias</small></div><div class="stat"><span class="symbol">◎</span><strong class="face-stat">${face==='REGISTRADO'?'Registrado':'Pendiente'}</strong><small>Perfil Face ID</small></div><div class="stat"><span class="symbol">✓</span><strong>${rows.filter(r=>r[5]==='SI').length}</strong><small>Matrículas vigentes</small></div></div>`+section('Acciones rápidas',`<div class="actions">${btn('Ver mi matrícula','goto-matricula')}${btn('Registrar asistencia','goto-asistencia','soft')}${btn('Mi Face ID','goto-faceid','soft')}</div>`));buttons();return}
if(state.tab==='perfil'){const rows=await api('miPerfil');const r=rows[0]||[];setMain(title('Mi información académica','Datos asociados a tu cuenta',btn('Editar contacto','edit-my-profile','soft'))+section('Perfil',table(['Cédula','Nombres','Apellidos','Correo','Teléfono','Nivel de referencia','Estado','Malla curricular'],[r.filter((_,i)=>i!==3)])));buttons();return}
if(state.tab==='historial'){const rows=await api('historialAcademico');setMain(title('Historial académico','Registros aprobados, reprobados y retirados importados desde el sistema')+section('Materias cursadas',table(['Código','Materia','Estado','Calificación','Período','Registrada'],rows)));return}
if(state.tab==='justificaciones'){const [clases,solicitudes]=await Promise.all([api('clasesParaJustificar'),api('misJustificaciones')]);state.clasesJustificar=clases;setMain(title('Justificaciones','Solicita la revisión de faltas de clases finalizadas')+section('Faltas disponibles',table(['Código','Materia','Clase','Fecha'],clases.map(r=>r.slice(1,5)),(r,i)=>action('Solicitar revisión','request-justification',i)))+section('Mis solicitudes',table(['Código','Materia','Clase','Motivo','Estado','Solicitada','Respuesta','Adjunto'],solicitudes.map(r=>r.slice(1,9)))));buttons();return}
if(state.tab==='matricula'){
  const [rows,ofertas,comprobantes]=await Promise.all([api('misMaterias'),api('ofertasDisponibles'),api('misPeriodosMatricula')]);
  state.periodosMatricula=comprobantes;
  state.inscripciones=rows;
  const elegibles=[...ofertas.map(r=>[r[0],r[1],r[2],r[5],r[6]]),
    ...rows.filter(r=>r[3]==='RESERVADA').map(r=>[r[0],r[1],r[2],r[8],r[9]])];
  state.enrollmentOptions=[...new Map(elegibles.map(r=>[r[0],r])).values()];
  const periodos=[...new Map(state.enrollmentOptions.map(r=>[r[3],[r[3],r[4]]])).values()];
  state.periodoSeleccionado=periodos.some(r=>r[0]===state.periodoSeleccionado)?state.periodoSeleccionado:periodos[0]?.[0]||'';
  setMain(title('Matrícula en línea','Selecciona un período, las materias y sus paralelos')
    +section('Estado de matrícula',table(['Código','Materia','Estado','Paralelo','Período'],rows.map(r=>[r[1],r[2],r[3],r[6],r[9]]),(r,i)=>r[2]==='RESERVADA'?action('Liberar reserva','release-legacy',i):''))
    +section('Oferta académica', '<div class="row"><label>Período académico'+select('student-period',periodos,'Selecciona período',1)
      +'</label><label>Materia<select id="student-subject"></select></label><label>Paralelo<select id="student-group"></select></label></div>'
      +'<div class="actions spaced">'+btn('Agregar a la selección','add-enrollment')+'</div><div id="offer-area"></div>')
    +section('Resumen de la selección','<div id="enrollment-cart"></div>')
    +section('Mis comprobantes',table(['Período','Estado','Solicitada','Confirmada','Materias'],comprobantes.map(r=>r.slice(1,6)),(_,i)=>action('Descargar PDF','download-enrollment',i))));
  $('#student-period').value=state.periodoSeleccionado;
  $('#student-period').addEventListener('change',()=>{
    state.periodoSeleccionado=$('#student-period').value;
    fillEnrollmentSubjects();
    renderEnrollmentCart();
  });
  $('#student-subject').addEventListener('change',()=>loadStudentOffer().catch(e=>toast(e.message,true)));
  fillEnrollmentSubjects();
  renderEnrollmentCart();
  return;
}
if(state.tab==='asistencia'){state.inscripciones=await api('misMaterias');const current=state.inscripciones.filter(r=>r[5]==='SI');setMain(title('Registro de asistencia','Marca tu asistencia con verificación Face ID')+section('Seleccionar materia',`<div class="row"><label>Materia${select('attendance-subject',current,'Selecciona materia',2)}</label></div><div id="attendance-area"></div>`));$('#attendance-subject').addEventListener('change',()=>loadAttendance().catch(error=>toast(error.message,true)));await loadAttendance();return}
if(state.tab==='faceid'){const face=(await api('estadoFacial'))[0]?.[0];setMain(title('Mi Face ID','Registro facial protegido')+section('Estado del perfil',`<div class="notice">${face==='REGISTRADO'?'Tu perfil facial ya está registrado. Puedes marcar asistencia en las clases abiertas.':'Todavía no has registrado tu rostro. Se almacenará una plantilla facial cifrada y la fecha de consentimiento, no la foto de registro.'}</div>${face==='REGISTRADO'?btn('Retirar consentimiento','withdraw-face','outline'):btn('Registrar mi rostro','register-face')}`));buttons();return}
if(state.tab==='horario'){const rows=await api('miHorario');setMain(title('Mi horario','Clases de tus materias')+section('Horario semanal',table(['Código','Materia','Paralelo','Día','Desde','Hasta','Aula','Período'],(await api('bloquesConfirmados')).map(r=>r.map((v,i)=>i===3?['','Lunes','Martes','Miércoles','Jueves','Viernes'][Number(v)]:v))))+section('Sesiones programadas',table(['Materia','Tema','Inicio','Fin','Paralelo'],rows.map(r=>[r[1],r[4],r[5],r[6],r[2]]))));return}}
function fillEnrollmentSubjects(){const rows=state.enrollmentOptions.filter(r=>r[3]===state.periodoSeleccionado);$('#student-subject').innerHTML=options(rows.map(r=>[r[0],r[1]+' · '+r[2]]),1,'Selecciona una asignatura');$('#student-group').innerHTML='<option value="">Selecciona materia primero</option>';$('#student-group').disabled=true;$('#offer-area').innerHTML=''}
async function loadStudentOffer() {
  const id = $('#student-subject')?.value;
  const target = $('#offer-area');
  const selector = $('#student-group');
  if (!target || !selector) return;
  selector.disabled = true;
  selector.replaceChildren(new Option(id?'Consultando paralelos…':'Selecciona materia primero',''));
  target.innerHTML = '';
  if (!id) return;
  const [rows,grupos,horarios] = await Promise.all([api('ofertaMatricula',id),api('paralelos',id),api('horariosOferta',id)]);
  if ($('#student-subject')?.value!==id || $('#offer-area')!==target) return;
  const activos = horarios.filter(r=>r[6]==='ACTIVO');
  const dias = ['','Lunes','Martes','Miércoles','Jueves','Viernes'];
  const programados = new Set(rows.map(r=>r[1]));
  const reservado = state.inscripciones.find(r=>r[0]===id&&r[3]==='RESERVADA')?.[6];
  selector.replaceChildren(new Option('Selecciona un paralelo',''));
  for (const [grupo,total,ocupados] of grupos) {
    const disponibles = Math.max(0,Number(total)-Number(ocupados));
    const conHorario = activos.some(r=>r[1]===grupo) || programados.has(grupo);
    const opcion = new Option(grupo+' · '+(grupo===reservado?'Cupo reservado':!conHorario?'Sin horario':disponibles?disponibles+' disponibles':'Sin cupos'),grupo);
    opcion.disabled = grupo!==reservado && (!conHorario || !disponibles);
    selector.add(opcion);
  }
  selector.disabled = ![...selector.options].some(o=>o.value&&!o.disabled);
  const aviso = selector.disabled ? '<div class="notice" role="status">No hay paralelos con horario y cupos disponibles para esta asignatura.</div>' : '';
  target.innerHTML = aviso+section('Cupos y clases',table(['Paralelo','Cupo máximo','Ocupados','Disponibles'],
    grupos.map(g=>[g[0],g[1],g[2],Math.max(0,Number(g[1])-Number(g[2]))])))
    +section('Horario semanal',table(['Paralelo','Día','Desde','Hasta','Aula'],activos.map(r=>[r[1],dias[Number(r[2])],r[3],r[4],r[5]])))
    +section('Sesiones programadas',table(['Parcial','Tema','Inicio','Fin','Paralelo'],rows.map(r=>[r[2],r[3],r[4],r[5],r[1]])));
}
function enrollmentCart(){
  state.enrollmentCarts ??= new Map();
  if(!state.enrollmentCarts.has(state.periodoSeleccionado))state.enrollmentCarts.set(state.periodoSeleccionado,new Map());
  return state.enrollmentCarts.get(state.periodoSeleccionado);
}
function renderEnrollmentCart(){
  const rows=[...enrollmentCart().values()];
  $('#enrollment-cart').innerHTML=table(['Código','Materia','Paralelo'],rows.map(r=>r.slice(1)),(_,i)=>action('Quitar','remove-enrollment',i))
    +(rows.length?'<div class="actions spaced">'+btn('Confirmar matrícula','confirm-enrollment')+'</div>':'');
  buttons();
}
async function loadAttendance(){const id=$('#attendance-subject')?.value;const target=$('#attendance-area');if(!target)return;if(!id){target.innerHTML='<div class="empty">Selecciona una materia legalizada.</div>';return}const [rows,resumen,parciales]=await Promise.all([api('horarios',id),api('resumenAsistencia',id),api('miListaParciales',id)]);if ($('#attendance-subject')?.value !== id || $('#attendance-area') !== target) return;state.horarios=rows;const avisos=rows.filter(r=>r[7]).map(r=>'<li>'+esc(r[2])+': coincide con '+esc(r[7])+'.</li>');const aviso=avisos.length?'<div class="notice" role="status"><strong>Clases con cruce de horario</strong><ul>'+avisos.join('')+'</ul>Informa al docente o a Administración.</div>':'';const x=resumen[0]||['0','0','0','0',''];target.innerHTML=aviso+section('Resumen de clases finalizadas',`<div class="grid"><div class="stat"><strong>${esc(x[1])}</strong><small>Asistencias</small></div><div class="stat"><strong>${esc(x[3])}</strong><small>Faltas sin justificar</small></div><div class="stat"><strong>${esc(x[5]||'0')}%</strong><small>Asistencia efectiva</small></div></div><p class="muted">Clases consideradas: ${esc(x[0])}. Atrasos: ${esc(x[2])}. Faltas justificadas: ${esc(x[4]||'0')}.</p><label>Parcial del reporte<select id="student-report-partial">${options(parciales,1,'Todos')}</select></label><div class="actions">${btn('Descargar reporte PDF','download-student-report','soft')}</div>`)+section('Próximas clases',table(['Parcial','Tema','Inicio','Fin','Estado','Paralelo'],rows.map(r=>r.slice(1,7)),(_,i)=>rows[i][5]==='ABIERTA'?action('Verificar y marcar','mark-attendance',i):''));buttons()}
async function cameraCapture(indicacion = 'Mira de frente a la cámara, con buena iluminación y sin otras personas.') {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: {facingMode:'user', width:{ideal:640}, height:{ideal:480}}, audio:false
    });
  } catch {
    throw Error('No se pudo abrir la cámara. Usa HTTPS o localhost y permite el acceso.');
  }
  const dialog = $('#modal');
  if (dialog.open) {
    stream.getTracks().forEach(track => track.stop());
    throw Error('Cierra la ventana abierta antes de capturar el rostro.');
  }
  $('#modal-title').textContent = 'Capturar fotografía';
  $('#modal-body').innerHTML = '<p class="muted">'+esc(indicacion)+'</p><video id="video" class="video" autoplay muted playsinline></video><p id="camera-feedback" role="status"></p><div class="camera-actions"><button id="take-photo" class="primary">Capturar foto</button><button id="cancel-photo" class="outline">Cancelar</button></div>';
  const video = $('#video');
  video.srcObject = stream;
  const tomar = $('#take-photo');
  return new Promise((resolve, reject) => {
    let terminada = false;
    const cerrar = () => {
      terminada = true;
      stream.getTracks().forEach(track => track.stop());
      video.srcObject = null;
      dialog.close();
    };
    const cancelar = () => {
      cerrar();
      resolve(null);
    };
    $('#cancel-photo').onclick = cancelar;
    $('#modal-close').onclick = cancelar;
    dialog.oncancel = event => {
      event.preventDefault();
      cancelar();
    };
    tomar.onclick = async () => {
      if (!video.videoWidth || !video.videoHeight) {
        $('#camera-feedback').textContent = 'Espera a que la cámara se active.';
        return;
      }
      tomar.disabled = true;
      try {
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.min(video.videoWidth, 960, Math.floor(1080 * video.videoWidth / video.videoHeight)));
        canvas.height = Math.max(1, Math.round(video.videoHeight * canvas.width / video.videoWidth));
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
        const blob = await new Promise(done => canvas.toBlob(done, 'image/jpeg', 0.8));
        if (terminada) return;
        if (!blob) throw Error('No se pudo capturar la foto.');
        if (blob.size > 750_000) throw Error('La foto supera 750 KB. Vuelve a iniciar la captura.');
        const bytes = new Uint8Array(await blob.arrayBuffer());
        if (terminada) return;
        let binary = '';
        for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
        cerrar();
        resolve({blob, base64:btoa(binary)});
      } catch (error) {
        if (terminada) return;
        cerrar();
        reject(error);
      }
    };
    dialog.showModal();
  });
}
async function faceDescriptor(blob){const response=await fetch('/api/rostro',{method:'POST',headers:{'Content-Type':'image/jpeg','X-Asistencia-Sesion':state.token},body:blob});const text=await response.text();if(!response.ok)throw Error(text);return text}
async function captureFace(register=false,classId=''){if(state.capturandoRostro)return;state.capturandoRostro=true;try{if(register&&!confirm('¿Aceptas registrar tu perfil facial? Se guardará una plantilla matemática cifrada y la fecha de consentimiento. La fotografía de registro no se conservará.'))return;const reto=(await api('desafioRostro',register?'REGISTRO':'ASISTENCIA'))[0];const frontal=await cameraCapture();if(!frontal)return;const indicacion=reto[1]==='IZQUIERDA'?'gira la cabeza hacia tu izquierda':'gira la cabeza hacia tu derecha';const giro=await cameraCapture('Ahora '+indicacion+' y mantén el rostro visible.');if(!giro)return;toast('Validando rostro y movimiento…');const validacion=(await api('validarRostro',reto[0],frontal.base64,giro.base64))[0];if(register)await perform('guardarPerfilFacial',validacion[0]);else await perform('marcar',classId,validacion[0])}finally{state.capturandoRostro=false}}
async function dispatch(cmd,i=0){try{if(cmd==='refresh')return render();if(cmd==='download-enrollment'){await downloadPdf('tipo=matricula&periodo='+encodeURIComponent(state.periodosMatricula[i][0]));return}if(cmd==='download-general'){await downloadPdf('tipo=asistencia&general=true');return}
if(cmd==='download-report'){
  const materia=$('#report-subject').value;
  if(!materia)throw Error('Selecciona una materia.');
  const filtros=new URLSearchParams({tipo:'asistencia',materia,parcial:$('#report-partial').value||'0',paralelo:$('#report-group').value});
  await downloadPdf(filtros.toString());return;
}if(cmd==='download-student-report'){await downloadPdf('tipo=asistencia&materia='+encodeURIComponent(document.getElementById('attendance-subject').value)+'&parcial='+encodeURIComponent($('#student-report-partial').value||'0'));return}if(cmd==='print-report'){window.print();return}if(cmd.startsWith('goto-'))return navigate(cmd.slice(5));if(cmd==='edit-my-profile'){const r=(await api('miPerfil'))[0];modal('Actualizar contacto',fields([{name:'correo',label:'Correo electrónico',value:r[4],type:'email'},{name:'telefono',label:'Teléfono',value:r[5]}]),async d=>perform('actualizarMiPerfil',formValue(d,'correo'),formValue(d,'telefono')));return}if(cmd==='edit-academic-config'){const rows=await api('configuracionAcademica');const cfg=Object.fromEntries(rows);modal('Reglas académicas configurables',fields([{name:'creditos',label:'Máximo de créditos (vacío = sin límite)',type:'number',min:'0',max:'200',value:cfg.max_creditos_matricula||''},{name:'repetir',label:'Permitir repetir materia aprobada',type:'select',value:cfg.repetir_materia_aprobada||'false',options:[['false','No'],['true','Sí']]},{name:'umbral',label:'Umbral informativo de asistencia (%)',type:'number',min:'0',max:'100',value:cfg.asistencia_umbral_porcentaje||''},{name:'atraso',label:'Contar atrasos como asistencia',type:'select',value:cfg.asistencia_atraso_cuenta||'true',options:[['true','Sí'],['false','No']]},{name:'justificada',label:'Excluir faltas justificadas del porcentaje',type:'select',value:cfg.asistencia_justificada_excluida||'true',options:[['true','Sí'],['false','No']]}]),async d=>perform('guardarConfiguracionAcademica',formValue(d,'creditos'),formValue(d,'repetir'),formValue(d,'umbral'),formValue(d,'atraso'),formValue(d,'justificada')));return}if(cmd==='new-malla'||cmd==='edit-malla'){const r=cmd==='edit-malla'?state.mallas[i]:['','','','','','ACTIVA'];modal('Malla curricular',fields([{name:'nombre',label:'Nombre de la malla',value:r[1],required:true,full:true},{name:'version',label:'Versión',value:r[2],required:true},{name:'desde',label:'Vigente desde',type:'date',value:r[3]},{name:'hasta',label:'Vigente hasta',type:'date',value:r[4]},{name:'estado',label:'Estado',type:'select',value:r[5]||'ACTIVA',options:[['ACTIVA','Activa'],['INACTIVA','Inactiva']]}]),async d=>perform('guardarMalla',r[0],formValue(d,'nombre'),formValue(d,'version'),formValue(d,'desde'),formValue(d,'hasta'),formValue(d,'estado')));return}if(cmd==='new-catalog'||cmd==='edit-catalog'){if(!state.selectedMalla)throw Error('Selecciona una malla curricular.');const r=cmd==='edit-catalog'?state.catalogo[i]:['','','','','','ACTIVA'];modal('Asignatura de catálogo',fields([{name:'codigo',label:'Código',value:r[1],required:true},{name:'nombre',label:'Nombre (según fuente institucional)',value:r[2],required:true,full:true},{name:'nivel',label:'Nivel académico',type:'number',min:'1',max:'16',value:r[3]},{name:'creditos',label:'Créditos',type:'number',min:'0',step:'0.25',value:r[4]},{name:'activa',label:'Estado',type:'select',value:r[5]==='INACTIVA'?'false':'true',options:[['true','Activa'],['false','Inactiva']]}]),async d=>perform('guardarCatalogo',r[0],state.selectedMalla,formValue(d,'codigo'),formValue(d,'nombre'),formValue(d,'nivel'),formValue(d,'creditos'),formValue(d,'activa')));return}if(cmd==='edit-prerequisites'){const r=state.catalogo[i];const previos=await api('prerrequisitos',r[0]);const choices=state.catalogo.filter(x=>x[0]!==r[0]).map(x=>[x[0],x[1]+' · '+x[2]]);modal('Prerrequisitos de '+r[1],fields([{name:'prerrequisito',label:'Asignatura relacionada',type:'select',options:choices,required:true,full:true},{name:'operacion',label:'Operación',type:'select',options:[['AGREGAR','Agregar prerrequisito'],['QUITAR','Quitar prerrequisito']]}])+`<p class="muted">Configurados: ${esc(previos.map(x=>x[1]+' · '+x[2]).join(', ')||'Ninguno')}</p>`,async d=>perform('guardarPrerrequisito',r[0],formValue(d,'prerrequisito'),formValue(d,'operacion')));return}if(cmd==='request-justification'){const row=state.clasesJustificar[i];modal('Solicitar justificación',fields([{name:'motivo',label:'Motivo de la solicitud',required:true,full:true},{name:'documento',label:'Documento de respaldo (opcional)',type:'file',accept:'.pdf,.jpg,.jpeg,.png',full:true}]),async d=>perform('solicitarJustificacion',row[0],formValue(d,'motivo'),await formFileBase64(d,'documento')));return}if(cmd==='view-justification-document'){const row=state.solicitudes[i];const result=(await api('documentoJustificacion',row[0]))[0];modal('Documento adjunto',enlaceDocumento(result,row[0]));return}if(cmd==='approve-justification'||cmd==='reject-justification'){const row=state.solicitudes[i];const estado=cmd==='approve-justification'?'APROBADA':'RECHAZADA';modal(estado==='APROBADA'?'Aprobar justificación':'Rechazar justificación',fields([{name:'respuesta',label:'Respuesta (opcional)',full:true}]),async d=>perform('resolverJustificacion',row[0],estado,formValue(d,'respuesta')));return}if(cmd==='offer-schedule'){await editOfferSchedule(i);return}if(cmd==='new-account'||cmd==='edit-account'){await editAccount(cmd==='edit-account'?state.cuentas[i]:null);return}
if(cmd==='enrollment-exception'){
  const periodo=state.semestres[i];
  const estudiantes=(await api('cuentas')).filter(a=>a[1]==='ESTUDIANTE'&&a[11]==='ACTIVO');
  modal('Excepción de plazo · '+periodo[1],fields([
    {name:'alumno',label:'Estudiante',type:'select',options:estudiantes.map(a=>[a[0],a[2]+' · '+a[3]]),required:true,full:true},
    {name:'hasta',label:'Válida hasta (vacío para retirar la excepción)',type:'date',max:periodo[3],full:true},
    {name:'motivo',label:'Motivo de la autorización',full:true}
  ]),async d=>perform('excepcionMatricula',periodo[0],formValue(d,'alumno'),formValue(d,'hasta'),formValue(d,'motivo')));
  return;
}
if(cmd==='new-period'||cmd==='edit-period'){const r=cmd==='edit-period'?state.semestres[i]:['','','','','','','MATRICULA_ABIERTA'];modal('Período académico',fields([{name:'nombre',label:'Nombre del período',value:r[1],required:true,full:true},{name:'desde',label:'Fecha de inicio',type:'date',value:r[2],required:true},{name:'hasta',label:'Fecha final',type:'date',value:r[3],required:true},{name:'matricula_desde',label:'Apertura de matrícula',type:'date',value:r[4]},{name:'matricula_hasta',label:'Cierre de matrícula',type:'date',value:r[5]},{name:'estado',label:'Estado',type:'select',value:r[6]||'MATRICULA_ABIERTA',options:[['PLANIFICADO','Planificado'],['MATRICULA_ABIERTA','Matrícula abierta'],['MATRICULA_CERRADA','Matrícula cerrada'],['EN_CURSO','En curso'],['FINALIZADO','Finalizado']]}]),async d=>perform('guardarSemestre',r[0],formValue(d,'nombre'),formValue(d,'desde'),formValue(d,'hasta'),formValue(d,'matricula_desde'),formValue(d,'matricula_hasta'),formValue(d,'estado')));return}
if(cmd==='new-subject'||cmd==='edit-subject'){
  const r=cmd==='edit-subject'?state.materias[i]:[];
  const [accounts,periods,mallas]=await Promise.all([api('cuentas'),api('semestres'),api('mallas')]);
  const grupos=await Promise.all(mallas.map(async m=>(await api('catalogo',m[0]))
    .filter(c=>c[5]==='ACTIVA'||c[0]===r[8]).map(c=>[c[0],m[1]+' · '+m[2]+' / '+c[1]+' · '+c[2]])));
  modal('Oferta académica',fields([
    {name:'catalogo',label:'Asignatura de catálogo',type:'select',options:grupos.flat(),value:r[8]||'',required:true,full:true},
    {name:'docente',label:'Docente',type:'select',value:r[4],options:accounts.filter(a=>a[1]==='DOCENTE'&&a[11]==='ACTIVO').map(a=>[a[0],a[3]]),required:true},
    {name:'periodo',label:'Período',type:'select',value:r[6],options:periods.filter(p=>p[0]!=='0').map(p=>[p[0],p[1]]),required:true},
    {name:'activa',label:'Estado',type:'select',value:r[9]==='INACTIVA'?'false':'true',options:[['true','Activa'],['false','Inactiva']]}
  ]),async d=>perform('guardarMateria',r[0]||'','','',formValue(d,'docente'),'',formValue(d,'periodo'),formValue(d,'catalogo'),formValue(d,'activa')));
  return;
}
if(cmd==='teacher-roster'){
  const r=state.materias[i];
  const alumnos=await api('matriculas',r[0]);
  modal('Estudiantes matriculados · '+r[1],table(['Cédula','Estudiante','Paralelo'],alumnos.map(a=>[a[1],a[2],a[7]])));
  return;
}

if (cmd==='new-partial'||cmd==='edit-period-partial') {
  const seleccionado = state.periodoParcialesDatos;
  if (!seleccionado) throw Error('Selecciona un período académico.');
  const [periodo,nombrePeriodo,inicioPeriodo,finPeriodo] = seleccionado;
  const actual = cmd==='edit-period-partial' ? state.parcialesPeriodo[i] : null;
  const disponibles = ['Primer parcial','Segundo parcial'].filter(nombre=>
    !state.parcialesPeriodo.some(r=>r[0]===nombre));
  if (!actual && !disponibles.length) throw Error('El período ya tiene los dos parciales. Usa Editar para cambiar sus fechas.');
  const nombres = actual ? [actual[0]] : disponibles;
  modal((actual?'Editar parcial':'Nuevo parcial')+' · '+nombrePeriodo,fields([
    {name:'nombre',label:'Parcial',type:'select',options:nombres.map(nombre=>[nombre,nombre])},
    {name:'desde',label:'Desde',type:'date',min:inicioPeriodo,max:finPeriodo,value:actual?.[1],required:true},
    {name:'hasta',label:'Hasta',type:'date',min:inicioPeriodo,max:finPeriodo,value:actual?.[2],required:true}
  ]),data=>perform('guardarParcialPeriodo',periodo,formValue(data,'nombre'),formValue(data,'desde'),formValue(data,'hasta')));
  return;
}
if (cmd==='start-class-now') {
  const oferta = state.selectedMateria;
  const paralelos = await api('paralelos',oferta);
  if (!paralelos.length) throw Error('La oferta no tiene paralelos activos.');
  modal('Iniciar clase ahora','<p class="muted">La asistencia se abre de inmediato durante 30 minutos.</p>'+fields([
    {name:'tema',label:'Tema',required:true,full:true},
    {name:'paralelo',label:'Paralelo',type:'select',options:paralelos.map(r=>[r[0],r[0]])}
  ]),async datos=>{
    const respuesta = await api('iniciarClaseAhora',oferta,formValue(datos,'paralelo'),formValue(datos,'tema'));
    state.selectedParcial = '';
    toast(respuesta[0][0]);
    await navigate('clases');
  });
  return;
}
if (cmd==='edit-class-topic') {
  const clase = state.clases[i];
  modal('Tema de la clase',fields([
    {name:'tema',label:'Tema',value:clase[1]==='Por definir'?'':clase[1],required:true,full:true}
  ]),data=>perform('guardarTemaClase',clase[0],formValue(data,'tema')));
  return;
}

if(cmd==='edit-cupos'){const rows=await api('cupos',state.selectedMateria);modal('Configurar paralelo',fields([{name:'codigo',label:'Código del paralelo',required:true,placeholder:'Ej. A, B, C o 01'},{name:'cupo',label:'Cupo máximo',type:'number',min:'0',max:'500',required:true}]),async d=>perform('configurarParalelo',state.selectedMateria,formValue(d,'codigo'),formValue(d,'cupo')));return}
if(cmd==='record-result'){
  const alumno=state.enrollments[i];
  modal('Resultado académico de '+alumno[2],fields([
    {name:'estado',label:'Resultado',type:'select',options:[['APROBADA','Aprobada'],['REPROBADA','Reprobada'],['RETIRADA','Retirada'],['HOMOLOGADA','Homologada']]},
    {name:'nota',label:'Calificación (opcional)',type:'number',min:'0',max:'100',step:'0.01'}
  ]),async d=>perform('guardarResultadoAcademico',alumno[0],state.selectedMateria,formValue(d,'estado'),formValue(d,'nota')));
  return;
}
if(cmd==='enable-student'){const rows=(await api('cuentas')).filter(r=>r[1]==='ESTUDIANTE');modal('Habilitar matrícula',fields([{name:'alumno',label:'Estudiante',type:'select',options:rows.map(r=>[r[0],r[3]]),full:true}]),async d=>perform('matricular',state.selectedMateria,formValue(d,'alumno'),'HABILITAR'));return}
if(['legalize','reject','withdraw'].includes(cmd)){const row=state.enrollments[i];const op={legalize:'LEGALIZAR',reject:'RECHAZAR',withdraw:'RETIRAR'}[cmd];if(confirm(`${op} matrícula de ${row[2]}?`))await perform('matricular',state.selectedMateria,row[0],op);return}
if(cmd==='view-enrollment-photo'){const row=state.enrollments[i];const result=await api('fotoMatricula',state.selectedMateria,row[0]);modal('Fotografía de matrícula',`<img class="preview" alt="Evidencia de matrícula" src="data:image/jpeg;base64,${esc(result[0][0])}">`);return}
if(cmd==='correct-attendance'){
  const fila=state.detalle[i], parcial=$('#report-partial').value;
  modal('Corregir asistencia de '+fila[3],fields([
    {name:'estado',label:'Estado corregido',type:'select',options:[['ASISTENCIA','Asistencia'],['ATRASO','Atraso'],['FALTA','Falta']]},
    {name:'motivo',label:'Motivo y respaldo de la corrección',required:true,full:true}
  ]),async d=>perform('corregirAsistencia',parcial,fila[0],fila[1],formValue(d,'estado'),formValue(d,'motivo')));
  return;
}
if(cmd==='justify'){const row=state.detalle[i];const id=$('#report-partial')?.value;modal('Justificar falta',fields([{name:'clase',label:'ID de clase',value:row[0],required:true},{name:'alumno',label:'ID de estudiante',value:row[1],required:true},{name:'motivo',label:'Motivo',required:true,full:true}]),async d=>perform('justificar',id,formValue(d,'clase'),formValue(d,'alumno'),formValue(d,'motivo')));return}
if(cmd==='release-legacy'){
  if(confirm('¿Liberar la reserva de esta asignatura?'))await perform('liberarCupo',state.inscripciones[i][0]);
  return;
}
if(cmd==='add-enrollment'){
  const id=$('#student-subject').value, grupo=$('#student-group').value;
  const oferta=state.enrollmentOptions.find(r=>r[0]===id&&r[3]===state.periodoSeleccionado);
  if(!oferta||!grupo)throw Error('Selecciona una materia y un paralelo.');
  enrollmentCart().set(id,[id,oferta[1],oferta[2],grupo]);renderEnrollmentCart();return;
}
if(cmd==='remove-enrollment'){const cart=enrollmentCart();cart.delete([...cart.keys()][i]);renderEnrollmentCart();return}
if(cmd==='confirm-enrollment'){
  const cart=enrollmentCart();
  if (!cart.size || state.confirmandoMatricula) return;
  const periodo = state.periodoSeleccionado;
  const seleccion = [...cart.values()].flatMap(r => [r[0], r[3]]);
  modal('Términos de matrícula', '<form><p>Al enviar la solicitud confirmas las asignaturas y los paralelos del resumen. Los cupos quedan reservados y la matrícula se legaliza automáticamente al cierre del día, según la hora del servidor. El horario confirmado se habilita después de la legalización.</p><label class="terms-checkbox"><input type="checkbox" name="aceptacion" required> Acepto los términos de matrícula.</label><button class="primary" type="submit">Enviar solicitud</button></form>', async data => {
    if (!data.has('aceptacion') || state.confirmandoMatricula) return;
    state.confirmandoMatricula = true;
    try {
      const respuesta = await api('confirmarSeleccion', periodo, TERMS_VERSION, ...seleccion);
      cart.clear();
      toast(respuesta[0][0]);
      await navigate('matricula');
    } finally {
      state.confirmandoMatricula = false;
    }
  });
  return;
}
if(cmd==='withdraw-face'){
  if(confirm('¿Retirar el consentimiento y eliminar tu plantilla facial?'))await perform('retirarFaceId');return;
}
if(cmd==='register-face'){await captureFace(true);return}
if(cmd==='mark-attendance'){const row=state.horarios[i];await captureFace(false,row[0]);return}
}catch(error){toast(error.message,true)}}
async function editAccount(account) {
  const r = account || [];
  const docenteAnterior = r[1]==='DOCENTE' && !/^[0-9]{10}$/.test(r[2]);
  const mallas = await api('mallas');
  const estadosComunes = [['ACTIVO','Activo'], ['INACTIVO','Inactivo']];
  const estadosEstudiante = [...estadosComunes, ['EGRESADO','Egresado'], ['RETIRADO','Retirado']];
  modal('Cuenta de usuario', fields([
    {name:'rol', label:'Rol', type:'select', value:r[1]||'ESTUDIANTE', disabled:!!account, options:[['ESTUDIANTE','Estudiante'], ['DOCENTE','Docente']]},
    {name:'usuario', label:'Cédula', value:docenteAnterior?'':r[2], required:!docenteAnterior, placeholder:docenteAnterior?'Ingresa la cédula para actualizar el registro':''},
    {name:'nombre', label:'Nombres', value:r[4], required:true},
    {name:'apellidos', label:'Apellidos', value:r[5], required:true},
    {name:'clave', label:'Contraseña', type:'password', required:!account, placeholder:account?'Dejar vacía para conservarla':'Mínimo 8 caracteres'},
    {name:'correo', label:'Correo de contacto', type:'email', value:r[7]},
    {name:'institucional', label:'Correo institucional UTA', type:'email', value:r[12]?r[12]+'@uta.edu.ec':'', required:!account, placeholder:'nombre@uta.edu.ec'},
    {name:'telefono', label:'Teléfono', type:'tel', value:r[8]},
    {name:'malla', label:'Malla curricular', type:'select', value:r[9], options:[['','Sin asignar'], ...mallas.map(m=>[m[0],m[1]+' · '+m[2]])]},
    {name:'nivel', label:'Nivel de referencia', type:'number', min:'1', max:'16', value:r[10]},
    {name:'estado', label:'Estado', type:'select', value:r[11]||'ACTIVO', options:estadosEstudiante}
  ]), data => perform('guardarCuenta', r[0]||'', r[1]||formValue(data,'rol'), formValue(data,'usuario')||r[2],
    formValue(data,'nombre'), formValue(data,'apellidos'), formValue(data,'clave'), '',
    ...['correo','telefono','malla','nivel','estado','institucional'].map(name=>formValue(data,name))));

  const form = $('#modal-body form');
  const {rol, usuario, clave, estado} = form.elements;
  usuario.inputMode = 'numeric';
  usuario.pattern = '[0-9]{10}';
  usuario.title = 'Ingresa una cédula de 10 dígitos.';
  form.elements.institucional.pattern = '[A-Za-z0-9._\\-]{3,64}@[uU][tT][aA]\\.[eE][dD][uU]\\.[eE][cC]';
  clave.minLength = 8;
  clave.maxLength = 128;

  function actualizarRol() {
    const estudiante = rol.value === 'ESTUDIANTE';
    for (const nombre of ['malla','nivel']) {
      const campo = form.elements[nombre];
      campo.disabled = !estudiante;
      campo.closest('label').hidden = !estudiante;
    }
    const anterior = estado.value;
    const permitidos = estudiante ? estadosEstudiante : estadosComunes;
    estado.replaceChildren(...permitidos.map(([valor,etiqueta])=>new Option(etiqueta,valor)));
    estado.value = permitidos.some(([valor])=>valor===anterior) ? anterior : 'ACTIVO';
  }
  rol.addEventListener('change', actualizarRol);
  actualizarRol();
}

async function editOfferSchedule(i){const oferta=state.materias[i];const [rows,paralelos]=await Promise.all([api('horariosOferta',oferta[0]),api('paralelos',oferta[0])]);state.horariosOferta=rows;const dias=[['1','Lunes'],['2','Martes'],['3','Miércoles'],['4','Jueves'],['5','Viernes']];const registroOptions=[['','Nuevo horario'],...rows.map(r=>[r[0],dias[Number(r[2])-1]?.[1]+' · '+r[3]+'–'+r[4]+' · '+r[1]])];if(state.role==='DOCENTE'){modal('Horarios semanales',table(['Paralelo','Día','Desde','Hasta','Aula'],rows.map(r=>[r[1],dias[Number(r[2])-1],r[3],r[4],r[5]])));return}modal('Horario semanal · '+oferta[1],fields([{name:'registro',label:'Horario a editar',type:'select',options:registroOptions},{name:'paralelo',label:'Paralelo',type:'select',options:paralelos.map(r=>[r[0],r[0]])},{name:'dia',label:'Día',type:'select',options:dias},{name:'inicio',label:'Hora de inicio',type:'time',required:true,value:'08:00'},{name:'fin',label:'Hora de fin',type:'time',required:true,value:'09:00'},{name:'aula',label:'Aula o laboratorio'},{name:'activo',label:'Estado',type:'select',value:'true',options:[['true','Activo'],['false','Inactivo']]}])+`<p class="muted">${esc(rows.length?rows.map(r=>dias[Number(r[2])-1]?.[1]+' '+r[3]+'–'+r[4]+' · '+r[1]+' · '+r[5]).join('<br>'):'Todavía no hay horarios semanales.')}</p>`,async d=>perform('guardarHorarioOferta',formValue(d,'registro'),oferta[0],formValue(d,'paralelo'),formValue(d,'dia'),formValue(d,'inicio'),formValue(d,'fin'),formValue(d,'aula'),formValue(d,'activo')));const sync=()=>{const registro=$('#modal-body select[name="registro"]').value;const r=rows.find(x=>x[0]===registro);if(!r)return;$('#modal-body select[name="paralelo"]').value=r[1];$('#modal-body select[name="dia"]').value=r[2];$('#modal-body input[name="inicio"]').value=r[3];$('#modal-body input[name="fin"]').value=r[4];$('#modal-body input[name="aula"]').value=r[5];$('#modal-body select[name="activo"]').value=r[6]==='ACTIVO'?'true':'false'};$('#modal-body select[name="registro"]').addEventListener('change',sync);}
async function logout(){try{await api('salir')}catch(_){}state.token='';state.enrollmentCarts?.clear();state.role='';state.name='';state.selectedMateria='';state.selectedParcial='';$('#shell').hidden=true;$('#auth').hidden=false;$('#login-password').value='';switchRegister(false)}
$('#login-form').addEventListener('submit',login);$('#register-form').addEventListener('submit',register);$('#switch-register').addEventListener('click',()=>switchRegister(true));$('#logout').addEventListener('click',logout);$('#mobile-menu').addEventListener('click',()=>$('.sidebar').classList.toggle('open'));$('#modal-close').addEventListener('click',()=>$('#modal').close());
