// Headless UI check (Phase 4). Run with the full stack up (docker compose up --build):
//   docker run --rm --ipc=host --network geoportal-mvp_default -v "$PWD:/w" -w /tmp -e BASE=http://frontend \
//     mcr.microsoft.com/playwright:v1.49.1-noble sh -c "npm i -s playwright@1.49.1 && cp /w/scripts/ui-check.mjs . && node ui-check.mjs"
// Screenshots → docs/screenshots/.
import { chromium } from 'playwright'

const BASE = process.env.BASE ?? 'http://frontend'
const SHOTS = '/w/docs/screenshots'
const results = []
const ok = (name, cond, extra = '') => { results.push([name, !!cond, extra]); console.log(`${cond ? 'PASS' : 'FAIL'}  ${name} ${extra}`) }

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ru-RU' })
const page = await ctx.newPage()
const errors = []
page.on('pageerror', e => errors.push(String(e)))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

const shot = (n) => page.screenshot({ path: `${SHOTS}/${n}.png` })
async function login(name) {
  if (await page.locator('.avatar-btn').count()) { await page.locator('.avatar-btn').click(); await page.getByRole('button', { name: 'Выйти' }).click() }
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  await page.getByRole('button', { name: /Войти через Infocom ID/ }).click()
  await page.locator('button.account', { hasText: name }).click()
  await page.locator('.avatar-btn').waitFor()
}

try {
  // 1. Map
  await page.goto(BASE + '/#/map')
  await page.locator('canvas.maplibregl-canvas').waitFor({ timeout: 20000 })
  await page.waitForTimeout(3000)
  ok('map canvas rendered', await page.locator('canvas.maplibregl-canvas').count() === 1)
  ok('layer tree loaded', await page.getByText('АТД: области', { exact: false }).count() > 0)
  await shot('01-map')

  // 2. Search «бишк» → card
  await page.getByPlaceholder('Поиск: населённый пункт').fill('бишк')
  await page.locator('ul.suggest li').first().waitFor()
  const first = await page.locator('ul.suggest li').first().innerText()
  ok('search suggests Бишкек first', first.startsWith('Бишкек'), JSON.stringify(first))
  await page.locator('ul.suggest li').first().click()
  await page.locator('.card').first().waitFor()
  ok('feature card opened', (await page.locator('.card').first().innerText()).includes('Бишкек'))
  await page.waitForTimeout(1500)
  await shot('02-search-card')

  // 3. Login as Динара Мамытова → import wizard
  await login('Динара Мамытова')
  await page.goto(BASE + '/#/my-layers')
  await page.getByRole('button', { name: '+ Импорт данных' }).click()
  const healthOpt = await page.locator('.modal select option', { hasText: 'Больниц' }).first().getAttribute('value')
  await page.locator('.modal select').first().selectOption(healthOpt)
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.locator('.modal input[type=file]').setInputFiles('/w/demo-data/samples/hospitals_kyrg06_zone3.zip')
  await page.getByRole('button', { name: 'Далее' }).click()
  await page.getByText('Исходная система координат').waitFor()
  const step3 = await page.locator('.modal').innerText()
  const sel = await page.locator('.modal select').first().evaluate(s => s.options[s.selectedIndex].text)
  ok('step 3 shows Kyrg-06 zone 3', /Kyrg-06.*(зона|zone)\s*3/i.test(sel + ' ' + step3), JSON.stringify(sel))
  await shot('03-import-step3-srs')
  await page.getByRole('button', { name: 'Далее' }).click()   // → step 4 mapping
  await page.getByRole('button', { name: 'Далее' }).click()   // → validate → step 5
  await page.locator('.modal .err, .modal .alert-ok').first().waitFor({ timeout: 30000 })
  const errs = await page.locator('.modal .err').allInnerTexts()
  ok('step 5 shows 1 blocking error', errs.length === 1 && (await page.locator('.modal').innerText()).includes('Проверка не пройдена'), JSON.stringify(errs))
  ok('Импортировать/Далее disabled', await page.getByRole('button', { name: 'Далее' }).isDisabled())
  await shot('04-import-step5-validation')
  await page.getByRole('button', { name: 'Отмена' }).click()

  // 4. Admin → Контроль зонирования → check for Бишкек
  await login('Администратор Геопортала')
  await page.goto(BASE + '/#/zoning')
  await page.getByRole('button', { name: 'Запустить проверку' }).waitFor()
  const terr = page.locator('aside select').first()
  const bishkek = await terr.locator('option', { hasText: 'Бишкек' }).first().getAttribute('value')
  await terr.selectOption(bishkek)
  await page.getByRole('button', { name: 'Запустить проверку' }).click()
  await page.locator('.box', { hasText: 'Последняя проверка' }).waitFor({ timeout: 30000 })
  await page.waitForTimeout(1500)
  const box = await page.locator('.box', { hasText: 'Последняя проверка' }).innerText()
  ok('zoning check Бишкек: 117 objects, 29 discrepancies', box.includes('Бишкек') && box.includes('Проверено объектов: 117') && box.includes('расхождений: 29'), JSON.stringify(box))
  await shot('05-zoning-bishkek')
} catch (e) {
  ok('script completed', false, String(e).split('\n')[0])
  await shot('99-failure').catch(() => {})
}
ok('no JS page errors', errors.length === 0, errors.slice(0, 5).join(' | '))
await browser.close()
const failed = results.filter(r => !r[1]).length
console.log(`\nUI: ${results.length - failed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
