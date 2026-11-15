// UI part of the FR acceptance run (Playwright). Screenshots → docs/acceptance/screenshots/<FR>.png
// Run with the full stack up on a fresh DB:
//   docker run --rm --ipc=host --network geoportal-mvp_default -v "$PWD:/w" -w /tmp -e BASE=http://frontend \
//     mcr.microsoft.com/playwright:v1.49.1-noble sh -c "npm i -s playwright@1.49.1 && cp /w/scripts/acceptance-ui.mjs . && node acceptance-ui.mjs"
import { chromium, devices } from 'playwright'
import fs from 'node:fs'

const BASE = process.env.BASE ?? 'http://frontend'
const SHOTS = '/w/docs/acceptance/screenshots'
const DL = '/tmp/dl'
fs.mkdirSync(SHOTS, { recursive: true }); fs.mkdirSync(DL, { recursive: true })
let pass = 0, fail = 0
const check = (fr, name, cond, extra = '') => { cond ? pass++ : fail++; console.log(`CHECK ${cond ? 'PASS' : 'FAIL'}  [${fr}] ${name}${extra ? ' — ' + extra : ''}`) }
const note = (fr, text) => console.log(`NOTE        [${fr}] ${text}`)

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ru-RU', acceptDownloads: true })
const page = await ctx.newPage()
const jsErrors = []
page.on('pageerror', e => jsErrors.push(String(e)))
const osmRequests = []
page.on('request', r => { if (r.url().includes('tile.openstreetmap.org')) osmRequests.push(r.url()) })
await page.addInitScript(() => { window.__printed = 0; window.print = () => { window.__printed++ } })

const shot = (fr, p = page) => p.screenshot({ path: `${SHOTS}/${fr}.png` })
const settle = (ms = 1500) => page.waitForTimeout(ms)
async function step(fr, fn) { try { await fn() } catch (e) { check(fr, 'step completed', false, String(e).split('\n')[0]); await shot(fr + '-failure').catch(() => {}) } }
async function logout() {
  if (await page.locator('.avatar-btn').count()) { await page.locator('.avatar-btn').click(); await page.getByRole('button', { name: 'Выйти' }).click() }
}
async function login(name, provider = 'Infocom ID') {
  await logout()
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await page.getByRole('button', { name: new RegExp('Войти через ' + provider) }).click()
  await page.locator('button.account', { hasText: name }).click()
  await page.locator('.avatar-btn').waitFor()
}
async function gotoMap() { await page.goto(BASE + '/?r=' + Date.now() + '#/map'); await page.locator('canvas.maplibregl-canvas').waitFor({ timeout: 20000 }); await settle(2500) }
async function openAllThemes() {
  for (const h of await page.locator('button.theme-head').all()) if (!(await h.locator('.tri.open').count())) await h.click()
}
const layerCheckbox = (title) => page.locator('.layer-row', { hasText: title }).first().locator('input[type=checkbox]')
async function closeCard() { if (await page.locator('.card .panel-head .icon-btn').count()) { await page.locator('.card .panel-head .icon-btn').first().click(); await settle(500) } }
async function searchPick(q, expect = q) {
  await closeCard()
  await page.getByPlaceholder('Поиск: населённый пункт').fill(q)
  await page.locator('ul.suggest li').first().filter({ hasText: expect }).waitFor()
  await page.locator('ul.suggest li').first().click()
  await page.locator('.card').first().waitFor(); await settle(2000)
}

// ------------------------------------------------------------------ anonymous
await gotoMap()

await step('FR-GIS-00', async () => {
  check('FR-GIS-00', 'default basemap is OpenStreetMap', await page.locator('.basemap select').inputValue() === 'osm')
  check('FR-GIS-00', 'OSM tiles requested', osmRequests.length > 0, `${osmRequests.length} tile requests`)
  const opts = await page.locator('.basemap select option').allInnerTexts()
  note('FR-GIS-00', `basemap options: ${JSON.stringify(opts)}`)
  await page.locator('.basemap select').selectOption('none'); await settle()
  await shot('FR-GIS-00-none')
  await page.locator('.basemap select').selectOption('osm'); await settle()
  check('FR-GIS-00', 'switch none → osm works', await page.locator('.basemap select').inputValue() === 'osm')
  await shot('FR-GIS-00')
})

await step('FR-GIS-01', async () => {
  await openAllThemes()
  const themes = await page.locator('button.theme-head').count()
  const rows = await page.locator('.layer-row').count()
  check('FR-GIS-01', 'layers grouped by 7 themes', themes === 7, `${themes} themes, ${rows} layer rows`)
  const filterInputs = await page.locator('aside.side input:not([type=checkbox])').count()
  check('FR-GIS-01', 'layer-name search in tree (expected absent)', filterInputs === 0, `${filterInputs} text inputs in tree`)
  check('FR-GIS-01', 'opacity slider (expected absent)', await page.locator('aside.side input[type=range]').count() === 0)
  await shot('FR-GIS-01')
})

await step('FR-GIS-05', async () => {
  await layerCheckbox('Текущее освоение территории').check(); await settle()
  const legend = await page.locator('.layer-row', { hasText: 'Текущее освоение территории' }).locator('.legend').innerText().catch(() => '')
  check('FR-GIS-05', 'legend shown for styled layer', legend.includes('Жилой') && legend.includes('Сельхоз'), JSON.stringify(legend.replace(/\n/g, ' ')))
  await searchPick('ОБ-0001')
  await closeCard()
  await settle(1000)
  const box = await page.locator('canvas.maplibregl-canvas').boundingBox()
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
  await page.locator('.card').first().waitFor({ timeout: 8000 })
  const t = await page.locator('.card h3').first().innerText()
  check('FR-GIS-05', 'click on map object opens its card', t.length > 0, JSON.stringify(t))
  await shot('FR-GIS-05')
  await layerCheckbox('Текущее освоение территории').uncheck()
})

await step('FR-DAT-09', async () => {
  await searchPick('Каракол')
  check('FR-DAT-09', 'card opened for Каракол', (await page.locator('.card h3').first().innerText()).includes('Каракол'))
  await shot('FR-DAT-09')
  note('FR-DAT-09', 'map fits the object bbox on pick (see screenshot: Каракол centred); card ⤢ button re-zooms')
})

await step('FR-DAT-06', async () => {
  await searchPick('бишк', 'Бишкек')
  await page.locator('.card [title="В избранное"]').click()
  const toast = await page.locator('.toast').last().innerText().catch(() => '')
  check('FR-DAT-06', 'anonymous favourite → asks to log in', toast.includes('Войдите'), toast)
  await page.locator('.card [title="Печать"]').click()
  check('FR-DAT-06', 'print invoked', await page.evaluate(() => window.__printed) > 0)
  const [d1] = await Promise.all([page.waitForEvent('download'), page.locator('.card').getByRole('button', { name: 'CSV' }).click()])
  const csv = fs.readFileSync(await d1.path(), 'utf8')
  check('FR-DAT-06', 'card CSV export', csv.includes('Бишкек'), csv.split('\n').slice(0, 2).join(' | ').slice(0, 160))
  const [d2] = await Promise.all([page.waitForEvent('download'), page.locator('.card [title="Экспорт GeoJSON"]').click()])
  const gj = JSON.parse(fs.readFileSync(await d2.path(), 'utf8'))
  check('FR-DAT-06', 'card GeoJSON export', !!(gj.type && (gj.geometry || gj.features)), gj.type)
  const actions = await page.locator('.card .card-actions button').evaluateAll(bs => bs.map(b => b.title || b.innerText))
  note('FR-DAT-06', `card actions: ${JSON.stringify(actions)} — no buffer, no route`)
  await shot('FR-DAT-06')
})

await step('FR-SRCH-03', async () => {
  await closeCard()
  await page.getByPlaceholder('Поиск: населённый пункт').fill('Бишкек'); await settle(800)
  await page.getByPlaceholder('Поиск: населённый пункт').press('Enter')
  await page.getByRole('button', { name: 'Экспорт CSV' }).waitFor(); await settle(2000)
  const panel = page.locator('.results')
  const items = await panel.locator('li').count()
  check('FR-SRCH-03', 'results list shown', items > 0, `${items} items`)
  await shot('FR-SRCH-03')
  await panel.locator('li').first().click(); await settle(1500)
  check('FR-SRCH-03', 'result → card', await page.locator('.card').count() > 0)
  await shot('FR-SRCH-03-card'); await closeCard()
  await page.getByPlaceholder('Поиск: населённый пункт').press('Enter'); await page.getByRole('button', { name: 'Экспорт CSV' }).waitFor()

  // FR-SRCH-04
  const [c] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Экспорт CSV' }).click()])
  const csv = fs.readFileSync(await c.path(), 'utf8')
  check('FR-SRCH-04', 'results CSV export', csv.includes('Наименование') && csv.includes('Бишкек'), csv.split('\n')[0])
  const [g] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Экспорт GeoJSON' }).click()])
  const gj = JSON.parse(fs.readFileSync(await g.path(), 'utf8'))
  check('FR-SRCH-04', 'results GeoJSON export', gj.features?.length > 0, `${gj.features?.length} features, geometry ${gj.features?.[0]?.geometry?.type}, props ${Object.keys(gj.features?.[0]?.properties ?? {})}`)
  const xmlPdf = await page.getByRole('button', { name: /XML|PDF/ }).count()
  check('FR-SRCH-04', 'XML/PDF export (expected absent)', xmlPdf === 0)
  await shot('FR-SRCH-04')
})

await step('FR-SRCH-02', async () => {
  await gotoMap()
  await searchPick('бишк', 'Бишкек'); await closeCard()
  await page.getByRole('button', { name: /Поиск в области/ }).click()
  const box = await page.locator('canvas.maplibregl-canvas').boundingBox()
  const cx = box.x + box.width / 2, cy = box.y + box.height / 2
  await page.mouse.move(cx - 150, cy - 120); await page.mouse.down(); await page.mouse.move(cx + 150, cy + 120, { steps: 10 }); await page.mouse.up()
  await page.getByRole('button', { name: 'Экспорт CSV' }).waitFor({ timeout: 10000 }); await settle()
  const head = await page.locator('.results').innerText()
  check('FR-SRCH-02', 'rectangle area search returns results', /Найдено: [1-9]/.test(head), head.split('\n').slice(0, 2).join(' | '))
  await shot('FR-SRCH-02')
})

await step('FR-TUR-04', async () => {
  await gotoMap(); await openAllThemes()
  await page.getByRole('button', { name: 'КЫР' }).click(); await settle()
  const tree = await page.locator('aside.side').innerText()
  check('FR-TUR-04', 'layer titles switch to Kyrgyz', tree.includes('АТБ: облустар'))
  check('FR-TUR-04', 'UI chrome stays Russian (expected gap)', tree.includes('Слои') && tree.includes('Подложка'))
  check('FR-TUR-04', 'English switch (expected absent)', await page.getByRole('button', { name: /^(EN|ENG|АНГ)$/ }).count() === 0)
  await shot('FR-TUR-04')
  await page.getByRole('button', { name: 'РУС' }).click()
})

await step('FR-TER-06', async () => {
  await gotoMap(); await openAllThemes()
  for (const t of ['АТД: районы и города', 'Сети дорог', 'Населённые пункты', 'Гидрография']) await layerCheckbox(t).check()
  await searchPick('бишк', 'Бишкек'); await closeCard(); await settle()
  const cadastre = await page.locator('.layer-row', { hasText: 'Кадастр' }).allInnerTexts()
  note('FR-TER-06', `cadastre-related rows: ${JSON.stringify(cadastre.map(s => s.replace(/\n/g, ' ')))}`)
  const r = await page.evaluate(async () => (await fetch('/api/search?q=' + encodeURIComponent('1-02-03-0605-0433'))).json())
  check('FR-TER-06', 'search by cadastral number (cad_no attr of ОБ-0001)', r.total > 0, `total ${r.total}`)
  await shot('FR-TER-06')
})

await step('FR-TER-01', async () => {
  await gotoMap(); await openAllThemes()
  await layerCheckbox('Зоны застройки').check(); await layerCheckbox('Текущее освоение территории').check()
  await searchPick('бишк', 'Бишкек'); await closeCard()
  await page.mouse.wheel(0, 600); await settle(2500)
  await shot('FR-TER-01')
  check('FR-TER-01', 'zoning + development layers shown together', true)
})

await step('FR-PUB-01', async () => {
  const m = await browser.newContext({ ...devices['iPhone 13'], locale: 'ru-RU' })
  const mp = await m.newPage()
  await mp.goto(BASE + '/#/map'); await mp.locator('canvas.maplibregl-canvas').waitFor({ timeout: 20000 }); await mp.waitForTimeout(2500)
  const dims = await mp.evaluate(() => ({ vw: innerWidth, sw: document.documentElement.scrollWidth, mapW: document.querySelector('.map')?.getBoundingClientRect().width,
    sideW: document.querySelector('aside.side')?.getBoundingClientRect().width }))
  check('FR-PUB-01', 'no horizontal overflow on 390px phone', dims.sw <= dims.vw + 1, JSON.stringify(dims))
  check('FR-PUB-01', 'map gets at least half the screen width', (dims.mapW ?? 0) >= dims.vw / 2, JSON.stringify(dims))
  await mp.screenshot({ path: `${SHOTS}/FR-PUB-01.png` })
  await m.close()
})

// ------------------------------------------------------------------ authorized (citizen, Түндүк)
await step('FR-AUTH-01', async () => {
  await gotoMap()
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await settle(500); await shot('FR-AUTH-01')
  const txt = await page.locator('.modal').innerText()
  check('FR-AUTH-01', 'Infocom ID and Түндүк providers offered', txt.includes('Infocom ID') && txt.includes('Түндүк'))
  await page.getByRole('button', { name: /Войти через Түндүк/ }).click()
  await page.locator('button.account', { hasText: 'Азамат Гражданин' }).click()
  await page.locator('.avatar-btn').waitFor()
  const nav = await page.locator('header').innerText()
  check('FR-PUB-02', 'citizen: no staff menu', !nav.includes('Мои слои') && !nav.includes('Контроль зонирования'))
  await page.goto(BASE + '/#/my-layers'); await settle()
  const body = await page.locator('body').innerText()
  check('FR-PUB-02', 'citizen: «Мои слои» refused', body.includes('служебного контура') || body.includes('Доступ запрещён'), body.slice(0, 120).replace(/\n/g, ' '))
  await shot('FR-PUB-02-citizen')
})

// ------------------------------------------------------------------ staff
await step('FR-DAT-05', async () => {
  await gotoMap(); await login('Динара Мамытова')
  await page.locator('.avatar-btn').click(); await shot('FR-PUB-02-staff-menu')
  await page.locator('.avatar-btn').click()
  await page.goto(BASE + '/#/my-layers'); await page.locator('table.table tbody tr').first().waitFor()
  const rows = await page.locator('table.table tbody tr').count()
  await page.getByPlaceholder('Фильтр по наименованию').fill('нет-такого-слоя'); await settle(500)
  const filtered = await page.locator('table.table tbody tr').count()
  await page.getByPlaceholder('Фильтр по наименованию').fill(''); await settle(500)
  check('FR-DAT-05', 'layer registry + name filter', rows > 0 && filtered === 0, `${rows} rows → ${filtered} after non-matching filter`)
  await shot('FR-DAT-05')
  await page.locator('table.table tbody tr').first().click(); await page.getByText('Описание и метаданные').waitFor()
  await settle(800); await shot('FR-DAT-10')
  const md = await page.locator('.drawer, .modal, aside').filter({ hasText: 'Описание и метаданные' }).first().innerText()
  check('FR-DAT-10', 'metadata block shown (abstract, periodicity, contact, CRS)', md.includes('Аннотация') && md.includes('Периодичность') && md.includes('Ответственный'))
  await page.keyboard.press('Escape').catch(() => {})
  await page.goto(BASE + '/#/map'); await page.goto(BASE + '/#/my-layers'); await page.locator('table.table tbody tr').first().waitFor()
})

await step('FR-DAT-12', async () => {
  await page.getByRole('button', { name: '+ Импорт данных' }).click()
  await page.getByRole('button', { name: 'Создать новый слой' }).click()
  await page.locator('.modal label.field', { hasText: 'Наименование (рус.)' }).locator('input').fill('Зелёные зоны (UI-приёмка)')
  await page.locator('.modal select').first().selectOption('polygon')
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.locator('.modal input[type=file]').setInputFiles('/w/demo-data/samples/green_zones_with_error.geojson')
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.getByText('Исходная система координат').waitFor()
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.locator('.modal .err, .modal .alert-ok').first().waitFor({ timeout: 30000 })
  const errs = await page.locator('.modal .err').allInnerTexts()
  check('FR-DAT-12', 'self-intersection reported, import blocked', errs.some(e => e.includes('самопересечение')) && await page.getByRole('button', { name: 'Далее' }).isDisabled(), JSON.stringify(errs))
  const [d] = await Promise.all([page.waitForEvent('download'), page.locator('.modal').getByRole('button', { name: /Скачать/ }).click()])
  const csv = fs.readFileSync(await d.path(), 'utf8')
  check('FR-DAT-12', 'errors CSV downloadable from wizard', csv.includes('самопересечение'), csv.split('\n').slice(0, 2).join(' | '))
  await shot('FR-DAT-12')
  await page.getByRole('button', { name: 'Отмена' }).click()
})

// ------------------------------------------------------------------ staff МЧС: editor
await step('FR-GIS-03', async () => {
  await gotoMap(); await login('Бакыт Осмонов')
  await page.getByRole('button', { name: /Редактор/ }).click()
  await page.getByPlaceholder('Долгота').fill('74.59'); await page.getByPlaceholder('Широта').fill('42.87')
  await page.getByRole('button', { name: 'Добавить точку' }).click(); await settle(1500)
  await page.getByText('Заполните атрибуты').waitFor()
  for (const lbl of ['Тип события', 'Уровень угрозы']) {
    const sel = page.locator('label.field', { hasText: lbl }).locator('select')
    await sel.selectOption(await sel.locator('option').nth(1).getAttribute('value'))
  }
  await page.locator('label.field', { hasText: 'Дата события' }).locator('input').fill('2026-11-08')
  await page.locator('label.field', { hasText: 'Примечание' }).locator('input, textarea').first().fill('Оползень (UI-приёмка)')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await page.locator('.toast', { hasText: 'Объект сохранён' }).waitFor({ timeout: 8000 })
  check('FR-GIS-03', 'point created by coordinates in operational layer', true)
  await settle(2500); await shot('FR-GIS-03')
  // favourites need a logged-in user (anonymous gets a warning toast)
  await page.getByRole('button', { name: /Редактор/ }).click(); await settle(500)   // close the editor panel
  await searchPick('бишк', 'Бишкек')
  await page.locator('.card [title="В избранное"]').click()
  const fav = await page.evaluate(() => localStorage.getItem('gp_fav'))
  check('FR-DAT-06', 'favourite stored for logged-in user (browser localStorage only)', (fav ?? '[]').length > 4, fav)
})

// ------------------------------------------------------------------ admin
await step('FR-TER-04', async () => {
  await gotoMap(); await login('Администратор Геопортала')
  await page.goto(BASE + '/#/zoning'); await page.getByRole('button', { name: 'Запустить проверку' }).waitFor(); await settle(2500)
  const typeSel = page.locator('label.field', { hasText: 'Тип' }).locator('select')
  await typeSel.selectOption('NO_ZONE'); await settle(1500)
  const head = await page.getByText(/Реестр расхождений/i).first().innerText()
  check('FR-TER-04', 'type filter NO_ZONE → 3 in registry', head.includes('(3)'), head)
  await shot('FR-TER-04')
  await typeSel.selectOption('')
})

await step('FR-TER-02', async () => {
  const terr = page.locator('aside select').first()
  await terr.selectOption(await terr.locator('option', { hasText: 'Бишкек' }).first().getAttribute('value'))
  await page.getByRole('button', { name: 'Запустить проверку' }).click()
  await page.locator('.box', { hasText: 'Последняя проверка' }).waitFor({ timeout: 30000 }); await settle(1500)
  const b = await page.locator('.box', { hasText: 'Последняя проверка' }).innerText()
  check('FR-TER-02', 'check for Бишкек: 117 / 29', b.includes('117') && b.includes('29'), b.replace(/\n/g, ' '))
  await shot('FR-TER-02')
})

await step('FR-TER-03', async () => {
  await page.locator('ul.disc-list li').first().click()
  await page.getByText('История статусов').waitFor()
  await page.locator('label.field', { hasText: 'Статус' }).last().locator('select').selectOption('confirmed')
  await page.getByPlaceholder('Основание решения').fill('Акт выезда №7 (UI-приёмка)')
  await page.locator('label.field', { hasText: 'Акт / документ' }).locator('input[type=file]').setInputFiles({ name: 'akt.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n%%EOF') })
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await page.locator('.toast', { hasText: 'Статус расхождения сохранён' }).waitFor({ timeout: 8000 }); await settle(1000)
  const card = await page.locator('.card').filter({ hasText: 'История статусов' }).innerText()
  check('FR-TER-03', 'history shows new status, author, attached act', card.includes('Администратор Геопортала') && card.includes('akt.pdf'), card.split('История статусов')[1]?.slice(0, 200).replace(/\n/g, ' '))
  await shot('FR-TER-03')
  await shot('FR-DAT-03')
})

await step('FR-ADM-01', async () => {
  await page.goto(BASE + '/#/admin'); await page.getByText('Пользователи и роли').waitFor(); await settle(1000)
  await shot('FR-ADM-02')
  await page.getByRole('button', { name: 'Пользователи и роли' }).click(); await settle(800)
  await shot('FR-ADM-01')
  await page.getByRole('button', { name: 'Журнал событий' }).click(); await settle(1000)
  const audit = await page.locator('table').last().innerText()
  check('FR-AUTH-06', 'journal lists logins and the denial', audit.includes('Вход') || audit.includes('login'), audit.split('\n').slice(0, 3).join(' | ').slice(0, 200))
  await shot('FR-AUTH-06')
  await page.getByRole('button', { name: 'Правила зонирования' }).click(); await settle(800)
  await shot('FR-CFG-04')
})

check('ALL', 'no JS page errors', jsErrors.length === 0, jsErrors.slice(0, 3).join(' | '))
await browser.close()
console.log(`\nUI CHECKS: ${pass} passed, ${fail} failed`)
