<script setup lang="ts">
import * as THREE from 'three'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Props {
  class?: string
  bubbleCount?: number
  color1?: string
  color2?: string
  color3?: string
  speed?: number
  blur?: number
  interactive?: boolean
  minRadius?: number
  maxRadius?: number
  minOpacity?: number
  maxOpacity?: number
  spreadX?: number
  spreadY?: number
  depthMin?: number
  depthMax?: number
  driftStrength?: number
  cameraZ?: number
}

const props = withDefaults(defineProps<Props>(), {
  bubbleCount: 28,
  color1: '#78c4ff',
  color2: '#5d7cff',
  color3: '#8ef2ff',
  speed: 1,
  blur: 0,
  interactive: true,
  minRadius: 0.26,
  maxRadius: 1.28,
  minOpacity: 0.2,
  maxOpacity: 0.5,
  spreadX: 16,
  spreadY: 10,
  depthMin: -7,
  depthMax: 4,
  driftStrength: 1,
  cameraZ: 18,
})

const containerRef = ref<HTMLDivElement | null>(null)

let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let frameId = 0
const bubbles: Array<{
  mesh: THREE.Mesh<THREE.SphereGeometry, THREE.MeshPhysicalMaterial>
  baseX: number
  baseY: number
  depth: number
  drift: number
  phase: number
}> = []
const pointer = { x: 0, y: 0 }

const buildScene = () => {
  const container = containerRef.value
  if (!container) {
    return
  }

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(48, 1, 0.1, 100)
  camera.position.set(0, 0, props.cameraZ)

  renderer = new THREE.WebGLRenderer({
    antialias: true,
    alpha: true,
    powerPreference: 'high-performance',
  })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.8))
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.domElement.style.width = '100%'
  renderer.domElement.style.height = '100%'
  renderer.domElement.style.display = 'block'
  if (props.blur > 0) {
    renderer.domElement.style.filter = `blur(${props.blur}px)`
  }
  container.appendChild(renderer.domElement)

  const ambient = new THREE.AmbientLight('#d8ebff', 1.75)
  const point = new THREE.PointLight('#ffffff', 2.2, 80, 2)
  point.position.set(8, 6, 12)
  const rim = new THREE.PointLight('#85d5ff', 1.6, 70, 2)
  rim.position.set(-10, -4, 8)
  scene.add(ambient, point, rim)

  const palette = [props.color1, props.color2, props.color3]
  for (let index = 0; index < props.bubbleCount; index += 1) {
    const radius = THREE.MathUtils.randFloat(props.minRadius, props.maxRadius)
    const geometry = new THREE.SphereGeometry(radius, 40, 40)
    const material = new THREE.MeshPhysicalMaterial({
      color: palette[index % palette.length],
      transparent: true,
      opacity: THREE.MathUtils.randFloat(props.minOpacity, props.maxOpacity),
      roughness: 0.08,
      metalness: 0.02,
      transmission: 0.96,
      thickness: 1.2,
      ior: 1.12,
      reflectivity: 0.95,
      clearcoat: 1,
      clearcoatRoughness: 0.08,
    })

    const mesh = new THREE.Mesh(geometry, material)
    const baseX = THREE.MathUtils.randFloatSpread(props.spreadX)
    const baseY = THREE.MathUtils.randFloatSpread(props.spreadY)
    const depth = THREE.MathUtils.randFloat(props.depthMin, props.depthMax)
    const drift = THREE.MathUtils.randFloat(0.12, 0.44)
    const phase = THREE.MathUtils.randFloat(0, Math.PI * 2)

    mesh.position.set(baseX, baseY, depth)
    scene.add(mesh)
    bubbles.push({ mesh, baseX, baseY, depth, drift, phase })
  }

  resizeScene()
}

const resizeScene = () => {
  const container = containerRef.value
  if (!container || !renderer || !camera) {
    return
  }
  const width = container.clientWidth || 1
  const height = container.clientHeight || 1
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

const animate = (time: number) => {
  if (!scene || !camera || !renderer) {
    return
  }

  const elapsed = time * 0.00022 * props.speed

  bubbles.forEach((bubble, index) => {
    const waveX =
      Math.cos(elapsed * (0.65 + bubble.drift) + bubble.phase) *
      (0.5 + bubble.depth * -0.03) *
      props.driftStrength
    const waveY =
      Math.sin(elapsed * (0.95 + bubble.drift) + bubble.phase) *
      (0.9 + bubble.drift * 2.2) *
      props.driftStrength
    bubble.mesh.position.x = bubble.baseX + waveX + pointer.x * (0.18 + index * 0.002)
    bubble.mesh.position.y = bubble.baseY + waveY + pointer.y * (0.14 + index * 0.0015)
    bubble.mesh.rotation.x += 0.0018
    bubble.mesh.rotation.y += 0.0012
  })

  camera.position.x += ((props.interactive ? pointer.x * 0.9 : 0) - camera.position.x) * 0.035
  camera.position.y += ((props.interactive ? pointer.y * 0.6 : 0) - camera.position.y) * 0.035
  camera.lookAt(0, 0, 0)

  renderer.render(scene, camera)
  frameId = window.requestAnimationFrame(animate)
}

const handlePointerMove = (event: PointerEvent) => {
  if (!containerRef.value || !props.interactive) {
    return
  }
  const rect = containerRef.value.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width - 0.5) * 2
  pointer.y = -((event.clientY - rect.top) / rect.height - 0.5) * 2
}

const handlePointerLeave = () => {
  pointer.x = 0
  pointer.y = 0
}

const destroyScene = () => {
  window.cancelAnimationFrame(frameId)
  bubbles.forEach(({ mesh }) => {
    mesh.geometry.dispose()
    mesh.material.dispose()
    scene?.remove(mesh)
  })
  bubbles.length = 0
  renderer?.dispose()
  if (renderer?.domElement.parentNode) {
    renderer.domElement.parentNode.removeChild(renderer.domElement)
  }
  renderer = null
  scene = null
  camera = null
}

onMounted(() => {
  buildScene()
  window.addEventListener('resize', resizeScene)
  containerRef.value?.addEventListener('pointermove', handlePointerMove)
  containerRef.value?.addEventListener('pointerleave', handlePointerLeave)
  frameId = window.requestAnimationFrame(animate)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeScene)
  containerRef.value?.removeEventListener('pointermove', handlePointerMove)
  containerRef.value?.removeEventListener('pointerleave', handlePointerLeave)
  destroyScene()
})

watch(
  () =>
    [
      props.bubbleCount,
      props.color1,
      props.color2,
      props.color3,
      props.blur,
      props.minRadius,
      props.maxRadius,
      props.minOpacity,
      props.maxOpacity,
      props.spreadX,
      props.spreadY,
      props.depthMin,
      props.depthMax,
      props.driftStrength,
      props.cameraZ,
    ] as const,
  () => {
    if (!containerRef.value) {
      return
    }
    destroyScene()
    buildScene()
    frameId = window.requestAnimationFrame(animate)
  },
)
</script>

<template>
  <div ref="containerRef" :class="['bubbles-background', props.class]" aria-hidden="true"></div>
</template>

<style scoped>
.bubbles-background {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
</style>
