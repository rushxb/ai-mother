<template>
  <div ref="hostRef" class="ambient-tech-canvas" aria-hidden="true">
    <canvas ref="canvasRef" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  BufferAttribute,
  BufferGeometry,
  Clock,
  Color,
  Float32BufferAttribute,
  Group,
  IcosahedronGeometry,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  PerspectiveCamera,
  Points,
  PointsMaterial,
  Scene,
  TorusGeometry,
  WebGLRenderer,
} from 'three'

interface AmbientTechCanvasProps {
  density?: number
  accent?: string
  secondary?: string
  reducedMotionFallback?: boolean
}

const props = withDefaults(defineProps<AmbientTechCanvasProps>(), {
  density: 64,
  accent: '#2f8bff',
  secondary: '#56d7c5',
  reducedMotionFallback: true,
})

const hostRef = ref<HTMLDivElement>()
const canvasRef = ref<HTMLCanvasElement>()

let disposeScene: (() => void) | undefined
let isDisposed = false

onMounted(() => {
  const host = hostRef.value
  const canvas = canvasRef.value
  if (!host || !canvas) return

  if (isDisposed) return

  const scene = new Scene()
  const camera = new PerspectiveCamera(38, 1, 0.1, 100)
  camera.position.set(0, 0, 7.2)

  let renderer: WebGLRenderer
  try {
    renderer = new WebGLRenderer({
      canvas,
      alpha: true,
      antialias: true,
      powerPreference: 'low-power',
    })
  } catch (error) {
    console.warn('WebGL is unavailable; ambient scene has been disabled.', error)
    return
  }
  renderer.setClearColor(0x000000, 0)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.6))

  const world = new Group()
  scene.add(world)

  const pointCount = Math.max(24, Math.min(props.density, 120))
  const pointPositions = new Float32Array(pointCount * 3)
  const pointColors = new Float32Array(pointCount * 3)
  const accentColor = new Color(props.accent)
  const secondaryColor = new Color(props.secondary)
  const neutralColor = new Color('#b8c9df')

  for (let index = 0; index < pointCount; index += 1) {
    const positionIndex = index * 3
    pointPositions[positionIndex] = (Math.random() - 0.5) * 9.4
    pointPositions[positionIndex + 1] = (Math.random() - 0.5) * 5.8
    pointPositions[positionIndex + 2] = (Math.random() - 0.5) * 3.8

    const paletteColor = index % 5 === 0 ? secondaryColor : index % 2 === 0 ? accentColor : neutralColor
    pointColors[positionIndex] = paletteColor.r
    pointColors[positionIndex + 1] = paletteColor.g
    pointColors[positionIndex + 2] = paletteColor.b
  }

  const pointGeometry = new BufferGeometry()
  pointGeometry.setAttribute('position', new BufferAttribute(pointPositions, 3))
  pointGeometry.setAttribute('color', new BufferAttribute(pointColors, 3))
  const pointMaterial = new PointsMaterial({
    size: 0.055,
    transparent: true,
    opacity: 0.72,
    vertexColors: true,
    sizeAttenuation: true,
    depthWrite: false,
  })
  const points = new Points(pointGeometry, pointMaterial)
  world.add(points)

  const linePositions: number[] = []
  let connectionCount = 0
  const maxConnections = Math.min(180, pointCount * 2)
  for (let source = 0; source < pointCount && connectionCount < maxConnections; source += 1) {
    for (let target = source + 1; target < pointCount && connectionCount < maxConnections; target += 1) {
      const sourceIndex = source * 3
      const targetIndex = target * 3
      const dx = pointPositions[sourceIndex] - pointPositions[targetIndex]
      const dy = pointPositions[sourceIndex + 1] - pointPositions[targetIndex + 1]
      const dz = pointPositions[sourceIndex + 2] - pointPositions[targetIndex + 2]
      if (dx * dx + dy * dy + dz * dz > 1.45 * 1.45) continue

      linePositions.push(
        pointPositions[sourceIndex],
        pointPositions[sourceIndex + 1],
        pointPositions[sourceIndex + 2],
        pointPositions[targetIndex],
        pointPositions[targetIndex + 1],
        pointPositions[targetIndex + 2],
      )
      connectionCount += 1
    }
  }

  const lineGeometry = new BufferGeometry()
  lineGeometry.setAttribute('position', new Float32BufferAttribute(linePositions, 3))
  const lineMaterial = new LineBasicMaterial({
    color: accentColor,
    transparent: true,
    opacity: 0.12,
    depthWrite: false,
  })
  const lines = new LineSegments(lineGeometry, lineMaterial)
  world.add(lines)

  const coreGeometry = new IcosahedronGeometry(0.86, 1)
  const coreMaterial = new MeshBasicMaterial({
    color: accentColor,
    wireframe: true,
    transparent: true,
    opacity: 0.16,
  })
  const core = new Mesh(coreGeometry, coreMaterial)
  core.position.set(2.7, 0.65, -0.8)
  world.add(core)

  const orbitGeometry = new TorusGeometry(1.28, 0.012, 6, 96)
  const orbitMaterial = new MeshBasicMaterial({
    color: secondaryColor,
    transparent: true,
    opacity: 0.24,
  })
  const orbit = new Mesh(orbitGeometry, orbitMaterial)
  orbit.position.copy(core.position)
  orbit.rotation.set(1.08, 0.24, 0.18)
  world.add(orbit)

  const reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  const pointerTarget = { x: 0, y: 0 }
  const pointerCurrent = { x: 0, y: 0 }
  let frameId = 0
  let isVisible = true
  const clock = new Clock()

  const resize = () => {
    const width = Math.max(host.clientWidth, 1)
    const height = Math.max(host.clientHeight, 1)
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
  }

  const renderFrame = () => {
    frameId = 0
    if (!isVisible || document.hidden) return

    pointerCurrent.x += (pointerTarget.x - pointerCurrent.x) * 0.035
    pointerCurrent.y += (pointerTarget.y - pointerCurrent.y) * 0.035
    const elapsed = clock.getElapsedTime()

    world.rotation.y = pointerCurrent.x * 0.1 + elapsed * 0.012
    world.rotation.x = pointerCurrent.y * 0.06
    points.position.y = Math.sin(elapsed * 0.24) * 0.06
    core.rotation.x = elapsed * 0.08
    core.rotation.y = elapsed * 0.12
    orbit.rotation.z = elapsed * 0.05

    renderer.render(scene, camera)
    frameId = window.requestAnimationFrame(renderFrame)
  }

  const startRendering = () => {
    if (frameId || !isVisible || document.hidden) return
    if (reducedMotionQuery.matches && props.reducedMotionFallback) {
      renderer.render(scene, camera)
      return
    }
    clock.start()
    frameId = window.requestAnimationFrame(renderFrame)
  }

  const stopRendering = () => {
    if (!frameId) return
    window.cancelAnimationFrame(frameId)
    frameId = 0
    clock.stop()
  }

  const handlePointerMove = (event: PointerEvent) => {
    pointerTarget.x = (event.clientX / Math.max(window.innerWidth, 1) - 0.5) * 2
    pointerTarget.y = (event.clientY / Math.max(window.innerHeight, 1) - 0.5) * 2
  }

  const handleVisibilityChange = () => {
    if (document.hidden) stopRendering()
    else startRendering()
  }

  const handleMotionPreferenceChange = () => {
    stopRendering()
    startRendering()
  }

  const resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(host)

  const intersectionObserver = new IntersectionObserver(
    ([entry]) => {
      isVisible = entry?.isIntersecting ?? true
      if (isVisible) startRendering()
      else stopRendering()
    },
    { rootMargin: '120px' },
  )
  intersectionObserver.observe(host)

  window.addEventListener('pointermove', handlePointerMove, { passive: true })
  document.addEventListener('visibilitychange', handleVisibilityChange)
  reducedMotionQuery.addEventListener('change', handleMotionPreferenceChange)

  resize()
  startRendering()

  const disposableResources: Array<{ dispose: () => void }> = [
    pointGeometry,
    pointMaterial,
    lineGeometry,
    lineMaterial,
    coreGeometry,
    coreMaterial,
    orbitGeometry,
    orbitMaterial,
  ]

  disposeScene = () => {
    stopRendering()
    resizeObserver.disconnect()
    intersectionObserver.disconnect()
    window.removeEventListener('pointermove', handlePointerMove)
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    reducedMotionQuery.removeEventListener('change', handleMotionPreferenceChange)
    disposableResources.forEach((resource) => resource.dispose())
    renderer.dispose()
  }
})

onBeforeUnmount(() => {
  isDisposed = true
  disposeScene?.()
})
</script>

<style scoped>
.ambient-tech-canvas,
.ambient-tech-canvas canvas {
  width: 100%;
  height: 100%;
}

.ambient-tech-canvas {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.ambient-tech-canvas canvas {
  display: block;
  opacity: 0.84;
}

@media (prefers-reduced-motion: reduce) {
  .ambient-tech-canvas canvas {
    opacity: 0.54;
  }
}
</style>
