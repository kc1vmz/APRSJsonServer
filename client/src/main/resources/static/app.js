const $ = id => document.getElementById(id);
const pageLinks = [...$('pages').querySelectorAll('a')];

function showPage() {
  const selected = pageLinks.find(link => link.hash === location.hash) || pageLinks[0];
  for (const link of pageLinks) {
    $(link.hash.slice(1)).hidden = link !== selected;
    if (link === selected) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
}

window.addEventListener('hashchange', showPage);
showPage();

let viewKey;
let revision = 0;
let discoveryRevision = 0;
let connectionState;
let connectionPending = false;

async function api(action, fields) {
  const response = await fetch('/client/' + action, fields === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(fields)
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || 'Request failed (' + response.status + ')');
  }
  return response.status === 204 ? undefined : response.json();
}

async function action(run) {
  $('notice').textContent = '';
  try {
    await run();
  } catch (error) {
    $('notice').textContent = error.message;
  }
}

function serverUrl() {
  const address = $('server-address').value.trim();
  const port = $('server-port').valueAsNumber;
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error('Enter a packet API port from 1 to 65535.');
  const host = address.includes(':') && !address.startsWith('[') ? '[' + address + ']' : address;
  if (!/^(?:[a-z0-9.-]+|\[[a-f0-9:.]+\])$/i.test(host))
    throw new Error('Enter a hostname, IPv4 address, or IPv6 address.');
  return new URL('http://' + host + ':' + port);
}

function resetFeeds() {
  discoveryRevision++;
  $('feed-label').hidden = true;
  $('feed').replaceChildren();
}

$('server-address').oninput = resetFeeds;
$('server-port').oninput = resetFeeds;

$('discover').onclick = () => action(async () => {
  const request = ++discoveryRevision;
  const base = serverUrl();
  const feeds = await api('discover', {url: base.href});
  if (request !== discoveryRevision) return;
  $('feed-label').hidden = !feeds.length;
  $('feed').replaceChildren(...feeds.map(feed => {
    const label = [feed.label, feed.type, feed.producerStation, feed.health].filter(Boolean).join(' · ');
    const url = new URL(feed.streamUrl, base);
    if (url.origin !== base.origin) throw new Error('Feed URL points to a different origin');
    return new Option(label, url.href);
  }));
  if (feeds.length) $('stream').value = '';
});
$('feed').onchange = () => { $('stream').value = ''; };

function updateConnectionControls() {
  const connected = connectionState === 'Connected';
  const active = connected || connectionState === 'Connecting';
  $('connect').disabled = connectionPending || connectionState === undefined || active;
  $('disconnect').disabled = connectionPending || !active;
  $('producer-info').hidden = !connected;
  if (!connected && $('producer-details').open) $('producer-details').close();
}

async function changeConnection(run) {
  connectionPending = true;
  updateConnectionControls();
  try {
    await action(async () => {
      await run();
      await refresh();
    });
  } finally {
    connectionPending = false;
    updateConnectionControls();
  }
}

$('connection').onsubmit = event => {
  event.preventDefault();
  if ($('connect').disabled) return;
  changeConnection(async () => {
    const url = $('stream').value.trim() || $('feed').value
      || new URL('/api/v1/aprs/stream', serverUrl()).href;
    await api('connect', {url});
  });
};
$('disconnect').onclick = () => changeConnection(() => api('disconnect', {}));
$('producer-info').onclick = () => $('producer-details').showModal();

async function refresh() {
  const request = ++revision;
  try {
    const status = await api('status');
    if (request !== revision) return;
    connectionState = status.state;
    updateConnectionControls();
    const heartbeat = status.lastHeartbeat ? new Date(status.lastHeartbeat).toLocaleTimeString() : '—';
    $('state').textContent = status.state + ' · Packets: ' + status.packets
      + ' · Sequence gaps: ' + status.sequenceGaps + ' · Gap events: ' + status.gapEvents
      + ' · Last heartbeat: ' + heartbeat;
    $('hello').textContent = status.hello ? JSON.stringify(status.hello, null, 2) : 'Waiting for hello';
    const key = JSON.stringify(status.recent);
    if (key === viewKey) return;
    viewKey = key;
    $('packets').replaceChildren();
    for (const item of status.recent) {
      const entry = $('packets').insertRow();
      entry.insertCell().textContent = item.json.sequence ?? '—';
      const receiver = item.json.receiver;
      entry.insertCell().textContent = receiver?.interface || receiver?.station || '—';
      entry.insertCell().textContent = item.text || item.type;
      const button = document.createElement('button');
      button.textContent = 'Inspect';
      button.onclick = () => {
        $('raw').textContent = item.hex ? 'Packet bytes (hex)\n' + item.hex : '';
        $('json').textContent = JSON.stringify(item.json, null, 2);
        $('details').showModal();
      };
      entry.insertCell().append(button);
    }
  } catch (error) {
    if (request === revision) $('state').textContent = error.message;
  }
}

async function loadApplicationInformation() {
  try {
    const info = await api('application-information');
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
setInterval(refresh, 1500);
