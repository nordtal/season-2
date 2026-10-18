// Takes one screenshot of a BlueMap webapp for each variant.
// Usage: node screenshot.mjs <name>=<url> [<name>=<url> ...]
// Each url is a local webroot served over HTTP, for example by `caddy file-server` or the BlueMap CLI with -w.
import { chromium } from "playwright"

const browser = await chromium.launch({
  executablePath: "/opt/pw-browsers/chromium",
  // three.js needs WebGL, and the container has no GPU.
  args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
})
for (const arg of process.argv.slice(2)) {
  const [name, url] = arg.split("=", 2)
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.on("console", (message) => console.log(`${name}: ${message.type()}: ${message.text()}`))
  await page.goto(url)
  await page.waitForFunction(() => window.bluemap?.mapViewer?.data?.mapState === "loaded", null, { timeout: 60000 })
  // The tiles load after the map state turns to loaded.
  await page.waitForTimeout(5000)
  await page.screenshot({ path: `${name}.png` })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(1000)
  await page.screenshot({ path: `${name}-phone.png` })
  await page.close()
}
await browser.close()
