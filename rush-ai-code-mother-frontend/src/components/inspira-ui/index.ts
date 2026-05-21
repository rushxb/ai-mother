import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BubblesBackground } from './bubbles-background'
import { MorphingTabs } from './morphing-tabs'

const inspiraUiComponents = {
  BlurReveal,
  BubblesBackground,
  MorphingTabs,
}

export { BlurReveal, BubblesBackground, MorphingTabs, inspiraUiComponents }

export default {
  install(app: App) {
    Object.entries(inspiraUiComponents).forEach(([name, component]) => {
      app.component(name, component)
    })
  },
}
