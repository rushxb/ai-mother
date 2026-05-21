import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BubblesBackground } from './bubbles-background'

const inspiraUiComponents = {
  BlurReveal,
  BubblesBackground,
}

export { BlurReveal, BubblesBackground, inspiraUiComponents }

export default {
  install(app: App) {
    Object.entries(inspiraUiComponents).forEach(([name, component]) => {
      app.component(name, component)
    })
  },
}
