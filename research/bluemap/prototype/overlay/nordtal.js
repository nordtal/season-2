// Nordtal additions for an unchanged BlueMap webapp. webapp.conf lists this file under `scripts`.
// The webapp loads it after the maps, and `window.bluemap` is the running BlueMapApp.
;(() => {
  const brand = document.createElement("a")
  brand.className = "nordtal-brand"
  brand.href = "https://nordtal.eu"
  brand.innerHTML = '<img src="nordtal/logo.png" alt="Nordtal">'
  document.getElementById("app").appendChild(brand)

  // The zoom buttons are off for every visitor, whatever their saved setting says.
  window.bluemap.appState.controls.showZoomButtons = false
})()
