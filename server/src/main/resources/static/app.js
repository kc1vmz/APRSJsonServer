const $ = id => document.getElementById(id);
const form = $('settings');
const fields = form.elements;
const types = {
  APRS_IS: 'APRS-IS', KISS_TCP: 'KISS TCP', KISS_SERIAL: 'KISS Serial',
  TNC2_TCP: 'TNC2 TCP', TNC2_SERIAL: 'TNC2 Serial', KENWOOD_SERIAL: 'Kenwood Serial'
};
let editing;
let revision = 0;

async function api(path, method = 'GET', data) {
  const headers = {'X-APRS-Management': '1'};
  if (data !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch('/manage' + path, {
    method, headers, body: data === undefined ? undefined : JSON.stringify(data)
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.message || 'Request failed (' + response.status + ')');
  }
  return response.status === 204 ? undefined : response.json();
}

function row(table, values) {
  const entry = table.insertRow();
  for (const value of values) entry.insertCell().textContent = value;
  return entry;
}

function button(parent, label, run) {
  const element = document.createElement('button');
  element.textContent = label;
  element.onclick = run;
  parent.append(element);
}

async function refresh() {
  const request = ++revision;
  try {
    const view = await api('/status');
    if (request !== revision) return;
    if ($('producer-station').disabled) {
      $('producer-station').value = view.producerStation;
      $('producer-station').disabled = false;
      $('save-producer').disabled = false;
    }
    $('sources').replaceChildren();
    $('statistics').replaceChildren();
    for (const source of view.sources) {
      const config = source.config;
      const endpoint = config.serialDevice || config.host + ':' + config.port;
      const entry = row($('sources'), [config.label, config.active ? 'Active' : 'Paused',
        source.health, source.lastPacket ? new Date(source.lastPacket).toLocaleString() : '—']);
      const details = document.createElement('small');
      details.textContent = types[config.type] + ' · ' + endpoint;
      entry.cells[0].append(document.createElement('br'), details);
      const actions = document.createElement('div');
      actions.className = 'actions';
      entry.insertCell().append(actions);
      button(actions, 'Edit', () => openEditor(config));
      button(actions, config.active ? 'Pause' : 'Resume', () =>
        change('/sources/' + config.id + '/active', 'PUT', {active: !config.active}));
      button(actions, 'Delete', () => {
        if (confirm('Delete ' + config.label + '?')) change('/sources/' + config.id, 'DELETE');
      });
      row($('statistics'), [config.label, source.received, source.packetsLastMinute,
        source.rejected, source.reconnects, source.subscribers]);
    }
    if (!view.sources.length) row($('sources'), ['No connections configured']);
    $('totals').textContent = 'Active stream subscriptions: ' + view.activeSubscribers
      + ' · All sources feed: ' + view.combinedSubscribers
      + ' · Slow-client disconnects: ' + view.slowClientDisconnects;
    if ($('status').dataset.refreshError) {
      $('status').textContent = '';
      delete $('status').dataset.refreshError;
    }
  } catch (error) {
    if (request !== revision) return;
    $('status').dataset.refreshError = 'true';
    $('status').textContent = error.message;
  }
}

async function change(path, method, data) {
  try {
    await api(path, method, data);
    $('status').textContent = '';
    await refresh();
  } catch (error) {
    $('status').textContent = error.message;
  }
}

function showFields() {
  const type = fields.type.value;
  const serial = type.endsWith('_SERIAL');
  $('network').hidden = serial;
  $('serial').hidden = !serial;
  $('callsign').hidden = type !== 'APRS_IS' && type !== 'KENWOOD_SERIAL';
  $('callsign-label').textContent = type === 'APRS_IS' ? 'Callsign' : 'Kenwood MYCALL';
  $('source-producer').hidden = type === 'APRS_IS';
  fields.producerStation.required = type !== 'APRS_IS' && type !== 'KENWOOD_SERIAL';
  fields.producerStation.placeholder = type === 'KENWOOD_SERIAL' ? 'Defaults to MYCALL' : '';
  $('internet').hidden = type !== 'APRS_IS';
  $('initialization').hidden = !serial || type === 'KENWOOD_SERIAL';
  for (const input of form.querySelectorAll('input, select')) input.disabled = !!input.closest('[hidden]');
}

async function openEditor(config) {
  editing = config?.id;
  form.reset();
  fields.port.value = 14580;
  fields.baudRate.value = 9600;
  if (config) {
    for (const [key, value] of Object.entries(config)) {
      if (key === 'passcode' || key === 'active' || value == null || !fields[key]
          || (key === 'producerStation' && config.type === 'APRS_IS')) continue;
      fields[key].value = value;
    }
    fields.active.checked = config.active;
  }
  $('editor-title').textContent = editing ? 'Edit connection' : 'Add connection';
  $('editor-status').textContent = '';
  showFields();
  $('editor').showModal();
  try {
    const result = await api('/serial-ports');
    $('ports').replaceChildren(...result.ports.map(port => new Option(port.description, port.device)));
  } catch {
    // Manual serial entry is available.
  }
}

fields.type.onchange = showFields;
$('add').onclick = () => openEditor();
$('cancel').onclick = () => $('editor').close();
form.onsubmit = async event => {
  event.preventDefault();
  const values = Object.fromEntries(new FormData(form));
  if (values.type === 'KENWOOD_SERIAL' && !values.producerStation.trim()) {
    values.producerStation = values.username;
  }
  values.active = fields.active.checked;
  for (const name of ['port', 'baudRate']) if (values[name]) values[name] = Number(values[name]);
  try {
    await api(editing ? '/sources/' + editing : '/sources', editing ? 'PUT' : 'POST', values);
    $('editor').close();
    await refresh();
  } catch (error) {
    $('editor-status').textContent = error.message;
  }
};

$('producer-settings').onsubmit = async event => {
  event.preventDefault();
  const input = $('producer-station');
  const button = $('save-producer');
  const feedback = $('producer-status');
  button.disabled = true;
  try {
    const saved = await api('/producer', 'PUT', {station: input.value});
    input.value = saved.station;
    feedback.textContent = 'Saved. New stream connections will receive this station in hello.';
  } catch (error) {
    feedback.textContent = error.message;
  } finally {
    button.disabled = false;
  }
};

async function loadApplicationInformation() {
  try {
    const info = await api('/application-information');
    $('application-name').textContent = info.applicationName;
    $('application-version').textContent = 'Version ' + info.version;
    $('application-copyright').textContent = 'Copyright © ' + info.copyrightYear + ' ' + info.author;
    for (const [id, address, label] of [
      ['application-license', info.licenseUrl, info.licenseName],
      ['application-website', info.website, new URL(info.website).hostname]
    ]) {
      const url = new URL(address);
      if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Invalid application link');
      $(id).href = url.href;
      $(id).textContent = label;
    }
  } catch {
    $('application-info-error').hidden = false;
  }
}

loadApplicationInformation();
refresh();
setInterval(refresh, 3000);
