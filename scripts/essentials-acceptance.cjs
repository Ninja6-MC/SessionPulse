// Explicitly invoked isolated Paper test; never connects to an existing server.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const { EventEmitter } = require('node:events');
const { spawn, execFileSync } = require('node:child_process');
const EXPECTED_PLUGIN_SHA = '84d28cfd8d20e16bd37a34235ab3e564aee79f9476f588ee92a2279998b8525c';
const EXPECTED_ESSENTIALS_SHA = 'bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6';
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function boundedGrowth(before, after, elapsed, tolerance = 4) {
  return after - before >= elapsed - tolerance && after - before <= elapsed + tolerance;
}
function essentialsWarning(line) {
  const plain = line.replace(/\u001b\[[0-9;]*m/g, '');
  return plain.includes('[Essentials] You are running an unsupported server version!');
}
function fixtureText(text) {
  return /^SPX[AB]:(ONE|TWO|You do not have permission to do that\.|Your counted window: [0-9]+\.[0-9]h \([0-9]+ min\)\. Lifetime: [0-9]+\.[0-9]h\.)$/.test(text);
}
function config(prefix, mode = 'AUTO', idle = 300) {
  return `tracking:\n  window-reset-hours: 1\n  afk:\n    mode: ${mode}\n    idle-seconds: ${idle}\n  flush-interval-minutes: 1\nreminders:\n  prefix: '${prefix}'\n  milestones:\n    - minute: 1\n      message: ONE\n    - minute: 2\n      message: TWO\n  overtime:\n    enabled: false\nenforcement:\n  enabled: false\n`;
}
async function main() {
  const env = process.env;
  for (const name of ['SPULSE_TEST_SERVER_JAR', 'SPULSE_TEST_PLUGIN_JAR', 'SPULSE_TEST_ESSENTIALS_JAR', 'SPULSE_TEST_COMPILE_CP', 'BOT_MODULE_ROOT']) {
    if (!env[name]) throw new Error('Missing ' + name);
  }
  if (sha(env.SPULSE_TEST_PLUGIN_JAR) !== EXPECTED_PLUGIN_SHA) throw new Error('Candidate bytes do not match deployed 98e7568 artifact.');
  if (sha(env.SPULSE_TEST_ESSENTIALS_JAR) !== EXPECTED_ESSENTIALS_SHA) throw new Error('EssentialsX 2.22.0 checksum mismatch.');
  const version = env.SPULSE_TEST_VERSION || '1.21.11';
  const scope = env.SPULSE_TEST_SCOPE || 'full';
  const platform = env.SPULSE_TEST_PLATFORM || 'paper';
  if (!['paper', 'spigot'].includes(platform)) throw new Error('Unsupported fixture platform.');
  if (!['full', 'commands'].includes(scope)) throw new Error('Unsupported fixture scope.');
  if (!['1.21.11', '26.3'].includes(version)) throw new Error('Unsupported native fixture version.');
  if (version === '26.3' && !env.SPULSE_TEST_NATIVE_BOT_JAR) throw new Error('Native 26.3 bridge JAR required.');
  if (!env.SPULSE_TEST_SERVER_SHA || sha(env.SPULSE_TEST_SERVER_JAR) !== env.SPULSE_TEST_SERVER_SHA) throw new Error('Expected server SHA-256 must match verified official input.');
  const directory = path.resolve('build', 'essentials-acceptance-' + Date.now());
  fs.mkdirSync(directory);
  const serverDir = path.join(directory, 'server'); fs.mkdirSync(serverDir);
  const plugins = path.join(serverDir, 'plugins'); fs.mkdirSync(plugins);
  const pulseDir = path.join(plugins, 'SessionPulse'); fs.mkdirSync(pulseDir);
  const essentialsDir = path.join(plugins, 'Essentials'); fs.mkdirSync(essentialsDir);
  const java = env.SPULSE_TEST_JAVA || 'java';
  const javac = env.SPULSE_TEST_JAVAC || 'javac';
  const jar = env.SPULSE_TEST_JAR_TOOL || 'jar';
  const classes = path.join(directory, 'classes'); fs.mkdirSync(classes);
  execFileSync(javac, ['-encoding', 'UTF-8', '--release', '21', '-cp', env.SPULSE_TEST_COMPILE_CP + path.delimiter + env.SPULSE_TEST_PLUGIN_JAR, '-d', classes, path.join(__dirname, 'fixtures/essentials/EssentialsAcceptanceProbe.java')], { stdio: 'pipe' });
  fs.copyFileSync(path.join(__dirname, 'fixtures/essentials/plugin.yml'), path.join(classes, 'plugin.yml'));
  execFileSync(jar, ['--create', '--file', path.join(plugins, 'EssentialsAcceptanceProbe.jar'), '-C', classes, '.']);
  fs.copyFileSync(env.SPULSE_TEST_SERVER_JAR, path.join(serverDir, 'server.jar'));
  fs.copyFileSync(env.SPULSE_TEST_PLUGIN_JAR, path.join(plugins, 'SessionPulse.jar'));
  fs.copyFileSync(env.SPULSE_TEST_ESSENTIALS_JAR, path.join(plugins, 'Essentials.jar'));
  const nativeBotJar = path.join(directory, 'native-bot.jar');
  if (version === '26.3') fs.copyFileSync(env.SPULSE_TEST_NATIVE_BOT_JAR, nativeBotJar);
  const reserve = net.createServer(); await new Promise(resolve => reserve.listen(0, '127.0.0.1', resolve));
  const port = reserve.address().port; await new Promise(resolve => reserve.close(resolve));
  fs.writeFileSync(path.join(serverDir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(serverDir, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],"biome":"minecraft:plains","structure_overrides":[]}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=3\ndifficulty=peaceful\nspawn-monsters=false\ngamemode=creative\nspawn-protection=0\nallow-flight=true\n`);
  fs.writeFileSync(path.join(essentialsDir, 'config.yml'), 'auto-afk: 300\nauto-afk-kick: -1\nfreeze-afk-players: false\ncancel-afk-on-interact: true\ncancel-afk-on-move: true\nupdate-check: false\n');
  const configFile = path.join(pulseDir, 'config.yml'); fs.writeFileSync(configFile, config('SPXA:'));
  const result = { target: 'isolated ' + platform + ' ' + version, nativeClient: version, pluginSha256: sha(env.SPULSE_TEST_PLUGIN_JAR), essentialsSha256: sha(env.SPULSE_TEST_ESSENTIALS_JAR), serverSha256: sha(env.SPULSE_TEST_SERVER_JAR), serverInput: path.basename(env.SPULSE_TEST_SERVER_JAR), javaVersion: execFileSync(java, ['--version'], { encoding: 'utf8' }).split(/\r?\n/)[0], nativeBotSha256: version === '26.3' ? sha(nativeBotJar) : null, thresholdSeconds: 300, warnings: [], checks: [], samples: [], reminders: [], commandReplies: [], acceptanceScope: scope, status: 'RUNNING', scope: 'Disposable loopback server only; this receipt covers its named platform/version. No production instance or data changes.' };
  const output = path.join(directory, 'evidence.json'); const save = () => fs.writeFileSync(output, JSON.stringify(result, null, 2));
  let server, fatal, ready = false; const bots = new Map(); let run = 0;
  const log = fs.createWriteStream(path.join(directory, 'server.log'));
  const started = Date.now();
  function check(name, ok, evidence) { result.checks.push({ name, status: ok ? 'PASS' : 'FAIL', evidence }); save(); if (!ok) throw new Error(name); }
  async function wait(predicate, label, ms = 30000) {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (fatal) throw new Error(fatal); if (predicate()) return; await delay(100); }
    throw new Error('Timeout: ' + label);
  }
  function start() {
    ready = false; fatal = null; run++;
    server = spawn(java, ['-Xms256M', '-Xmx1G', '-jar', 'server.jar', '--nogui'], { cwd: serverDir, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let pending = '';
    function lines(chunk) {
      log.write(chunk); pending += chunk.toString(); let newline;
      while ((newline = pending.indexOf('\n')) >= 0) {
        const line = pending.slice(0, newline); pending = pending.slice(newline + 1);
        if (line.includes('Done (')) ready = true;
        if (line.includes('Loading Paper ') || line.includes('This server is running CraftBukkit version') || line.includes('This server is running Spigot version')) result.observedServerBanner = line.trim();
        if (essentialsWarning(line) && !result.warnings.includes('EssentialsX reports this server version unsupported.')) result.warnings.push('EssentialsX reports this server version unsupported.');
        if (line.includes('SPX_FIXTURE_ERROR')) fatal = 'Probe error; see isolated log.';
        const at = line.indexOf('SPX_SAMPLE ');
        if (at >= 0) result.samples.push({ run, second: Math.round((Date.now() - started) / 1000), ...JSON.parse(line.slice(at + 11)) });
      }
    }
    server.stdout.on('data', lines); server.stderr.on('data', lines);
    server.on('error', () => { fatal = 'Server process could not start.'; });
    server.on('exit', code => { if (code !== 0) fatal = 'Isolated server exited with ' + code; });
  }
  const command = text => server.stdin.write(text + '\n');
  async function sample() {
    const since = result.samples.length; command('spxfixture');
    await wait(() => new Set(result.samples.slice(since).map(row => row.name)).size === bots.size, 'probe samples');
    return Object.fromEntries(result.samples.slice(since).map(row => [row.name, row]));
  }
  const mineflayer = version === '1.21.11' ? require(path.join(env.BOT_MODULE_ROOT, 'mineflayer')) : null;
  const nbt = require(path.join(env.BOT_MODULE_ROOT, 'prismarine-nbt'));
  require('./nbt-utf8-reader.cjs').installNbtUtf8Reader(nbt, require(path.join(env.BOT_MODULE_ROOT, 'protodef')).utils.PartialReadError);
  function nativeBot(name) {
    const bot = new EventEmitter();
    const child = spawn(java, ['-Xmx128M', '-cp', nativeBotJar, 'com.ninja6.botclient.EssentialsCommandBot', String(port), name], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let pending = '', quitting = false;
    child.stdout.on('data', chunk => {
      pending += chunk.toString(); let newline;
      while ((newline = pending.indexOf('\n')) >= 0) {
        const line = pending.slice(0, newline).trim(); pending = pending.slice(newline + 1);
        if (/^PROTOCOL [A-Za-z0-9._-]+ [0-9]+$/.test(line)) { const fields = line.split(' '); result.observedClientProtocol = { minecraft: fields[1], protocol: Number(fields[2]) }; if (fields[1] !== version) bot.emit('error', new Error('Native client codec version mismatch.')); }
        else if (line === 'READY') bot.emit('spawn');
        else if (line.startsWith('MESSAGE ') && fixtureText(line.slice(8))) bot.emit('message', line.slice(8));
        else if (['INVALID_COMMAND', 'COMMAND_STREAM_FAILED'].includes(line)) bot.emit('error', new Error('Native command bridge failed.'));
      }
    });
    child.stderr.on('data', () => {}); // Do not retain arbitrary protocol/debug output.
    child.on('error', error => bot.emit('error', error));
    child.on('exit', () => { if (!quitting) bot.emit('error', new Error('Native client disconnected unexpectedly.')); });
    bot.chat = command => child.stdin.write(command.replace(/^\//, '') + '\n');
    bot.quit = () => { quitting = true; child.stdin.end('QUIT\n'); setTimeout(() => { if (child.exitCode === null) child.kill(); }, 3000).unref(); };
    return bot;
  }
  async function connect() {
    for (const name of ['SPXAuto', 'SPXFallback', 'SPXManual']) {
      const bot = version === '26.3' ? nativeBot(name) : mineflayer.createBot({ host: '127.0.0.1', port, username: name, version, auth: 'offline', hideErrors: true });
      bots.set(name, bot); let spawned = false;
      bot.once('spawn', () => { bot.physicsEnabled = false; spawned = true; });
      bot.on('error', () => { fatal = 'Fixture player error.'; });
      bot.on('message', message => { const text = message.toString(); if (fixtureText(text)) { const collection = /^SPX[AB]:(ONE|TWO)$/.test(text) ? result.reminders : result.commandReplies; collection.push({ run, name, second: Math.round((Date.now() - started) / 1000), text }); } });
      await wait(() => spawned, 'spawn ' + name);
    }
    await delay(2000);
  }
  async function disconnect() { for (const bot of bots.values()) bot.quit(); bots.clear(); await delay(2000); }
  async function stop() {
    if (!server || server.exitCode !== null) return;
    const child = server; const exited = new Promise(resolve => child.once('exit', resolve)); command('stop');
    await Promise.race([exited, delay(30000).then(() => { if (child.exitCode === null) child.kill(); })]);
  }
  let aborting = false;
  async function abort(reason) {
    if (aborting) return; aborting = true;
    result.status = 'FAIL'; result.error = reason; save();
    await disconnect(); await stop(); log.end(); process.exit(1);
  }
  const watchdog = setTimeout(() => abort('15-minute isolated test limit.'), 900000);
  const interrupt = () => abort('Interrupted by operator.');
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    save(); start(); await wait(() => ready, 'server boot', 240000);
    check('Runtime banner matches declared platform/version', Boolean(result.observedServerBanner && result.observedServerBanner.includes(version) && (platform === 'paper' ? result.observedServerBanner.includes('Loading Paper ') : result.observedServerBanner.includes('Spigot'))), result.observedServerBanner || 'No platform/version banner observed.');
    await connect();
    const initial = await sample(); const joined = Date.now();
    check('Essentials automatic threshold and effective permissions', initial.SPXAuto.threshold === 300 && initial.SPXAuto.autoPermission && !initial.SPXFallback.autoPermission && !initial.SPXManual.autoPermission && !initial.SPXManual.adminPermission, initial);
    const manual = bots.get('SPXManual');
    let replyStart = result.commandReplies.length; manual.chat('/spulse time');
    await wait(() => result.commandReplies.slice(replyStart).some(row => row.name === 'SPXManual' && row.text.startsWith('SPXA:Your counted window:')), 'own counted-time command reply');
    check('Normal player can read own counted time', result.commandReplies.slice(replyStart).some(row => row.name === 'SPXManual' && row.text.startsWith('SPXA:Your counted window:')), result.commandReplies.slice(replyStart));
    replyStart = result.commandReplies.length; manual.chat('/spulse time SPXAuto');
    await wait(() => result.commandReplies.slice(replyStart).some(row => row.name === 'SPXManual' && row.text === 'SPXA:You do not have permission to do that.'), 'nonadmin read-only denial');
    check('Nonadmin other-player time denial', result.commandReplies.slice(replyStart).some(row => row.name === 'SPXManual' && row.text === 'SPXA:You do not have permission to do that.'), result.commandReplies.slice(replyStart));
    if (scope === 'commands') { result.status = 'PASS_COMMANDS_ONLY'; return; }
    manual.chat('/essentials:afk'); await delay(3000);
    const paused = await sample(); await delay(20000); const still = await sample();
    check('Manual AFK pauses counted time', paused.SPXManual.essentialsAfk && still.SPXManual.essentialsAfk && still.SPXManual.seconds - paused.SPXManual.seconds <= 2, { before: paused.SPXManual, after: still.SPXManual });
    manual.chat('/essentials:afk'); await delay(3000); const resumed = await sample(); await delay(12000); const active = await sample();
    check('Manual AFK resumes counted time', !active.SPXManual.essentialsAfk && boundedGrowth(resumed.SPXManual.seconds, active.SPXManual.seconds, 12), { before: resumed.SPXManual, after: active.SPXManual });
    const activity = setInterval(() => manual.chat('/spulse time'), 15000);
    try {
      while ((await sample()).SPXManual.seconds < 70) await delay(3000);
      const beforeReload = await sample(); const reminderStart = result.reminders.length;
      fs.writeFileSync(configFile, config('SPXB:')); command('spulse reload'); await delay(2000);
      const afterReload = await sample(); await delay(20000); const afterTick = await sample();
      check('Reload preserves time and one ticking schedule', afterReload.SPXManual.seconds >= beforeReload.SPXManual.seconds && boundedGrowth(afterReload.SPXManual.seconds, afterTick.SPXManual.seconds, 20), { before: beforeReload.SPXManual, afterReload: afterReload.SPXManual, afterTick: afterTick.SPXManual });
      while ((await sample()).SPXManual.seconds < 130) await delay(3000);
      const relevant = result.reminders.filter(row => row.name === 'SPXManual' && row.run === 1);
      check('Reload preserves fired milestones and new-prefix future reminder', relevant.filter(row => row.text === 'SPXA:ONE').length === 1 && relevant.filter(row => row.text === 'SPXB:TWO').length === 1 && !result.reminders.slice(reminderStart).some(row => row.name === 'SPXManual' && row.text.endsWith(':ONE')), relevant);
      const remaining = 350000 - (Date.now() - joined); if (remaining > 0) await delay(remaining);
      const idle = await sample(); await delay(25000); const capped = await sample();
      check('AUTO Essentials permission versus built-in fallback after 300 seconds', idle.SPXAuto.essentialsAfk && !idle.SPXFallback.essentialsAfk && !capped.SPXFallback.autoPermission && idle.SPXAuto.seconds >= 290 && idle.SPXAuto.seconds <= 315 && idle.SPXFallback.seconds >= 290 && idle.SPXFallback.seconds <= 315 && capped.SPXAuto.seconds - idle.SPXAuto.seconds <= 2 && capped.SPXFallback.seconds - idle.SPXFallback.seconds <= 2, { before: idle, after: capped });
      bots.get('SPXAuto').chat('/essentials:afk'); bots.get('SPXFallback').chat('/spulse time'); await delay(3000); const idleResume = await sample(); await delay(15000); const moving = await sample();
      check('Automatic AFK and fallback resume on input', !moving.SPXAuto.essentialsAfk && boundedGrowth(idleResume.SPXAuto.seconds, moving.SPXAuto.seconds, 15) && boundedGrowth(idleResume.SPXFallback.seconds, moving.SPXFallback.seconds, 15), { before: idleResume, after: moving });
    } finally { clearInterval(activity); }
    fs.writeFileSync(configFile, config('SPXB:', 'ESSENTIALS', 30)); command('spulse reload'); await delay(2000);
    const essentialsOnly = await sample(); await delay(45000); const essentialsAfter = await sample();
    check('ESSENTIALS mode does not apply built-in fallback without automatic permission', !essentialsAfter.SPXFallback.essentialsAfk && boundedGrowth(essentialsOnly.SPXFallback.seconds, essentialsAfter.SPXFallback.seconds, 45), { before: essentialsOnly.SPXFallback, after: essentialsAfter.SPXFallback, builtInIdleSeconds: 30 });
    fs.writeFileSync(configFile, config('SPXB:')); command('spulse reload'); await delay(2000);
    const beforeRestart = await sample(); await disconnect(); await stop();
    const persisted = fs.readFileSync(path.join(pulseDir, 'data.yml'), 'utf8');
    fs.writeFileSync(path.join(directory, 'data-before-restart.yml'), persisted);
    check('Clean stop writes counted player data', ['SPXAuto', 'SPXFallback', 'SPXManual'].every(name => persisted.includes(name)), 'Stored all three disposable players; retained YAML artifact.');
    start(); await wait(() => ready, 'server restart', 240000); await connect(); const afterRestart = await sample(); await delay(12000);
    check('Restart retains counted windows without reminder replay', Object.keys(beforeRestart).every(name => afterRestart[name].seconds >= beforeRestart[name].seconds && afterRestart[name].seconds <= beforeRestart[name].seconds + 5) && !result.reminders.some(row => row.run === 2), { before: beforeRestart, after: afterRestart });
    result.status = 'PASS';
  } catch (error) { result.status = 'FAIL'; result.error = error.message; process.exitCode = 1; }
  finally { clearTimeout(watchdog); process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt); await disconnect(); await stop(); result.durationSeconds = Math.round((Date.now() - started) / 1000); save(); log.end(); console.log('Evidence: ' + output + ' (' + result.status + ')'); }
}
module.exports = { boundedGrowth, config, fixtureText, essentialsWarning };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
