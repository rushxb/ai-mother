import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BoxReveal } from './box-reveal'
import { BubblesBackground } from './bubbles-background'
import { MorphingTabs } from './morphing-tabs'

const inspiraUiComponents = {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  MorphingTabs,
}

export { BlurReveal, BoxReveal, BubblesBackground, MorphingTabs, inspiraUiComponents }

export default {
  install(app: App) {
    Object.entries(inspiraUiComponents).forEach(([name, component]) => {
      app.component(name, component)
    })
  },
}
