<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

export interface ChartSeries {
  name: string
  color: string
  values: (number | null)[]
}
const props = withDefaults(
  defineProps<{
    categories: string[]
    series: ChartSeries[]
    format: (n: number | null) => string
    axisFormat?: (n: number) => string
    height?: number
    label: string
  }>(),
  { height: 280, axisFormat: undefined },
)

const root = ref<HTMLDivElement>()
const width = ref(720)
const hover = ref<number | null>(null)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(([entry]) => (width.value = Math.max(320, entry.contentRect.width)))
  if (root.value) observer.observe(root.value)
})
onBeforeUnmount(() => observer?.disconnect())

const pad = { top: 12, right: 8, bottom: 30, left: 64 }
const plotW = computed(() => width.value - pad.left - pad.right)
const plotH = computed(() => props.height - pad.top - pad.bottom)

/** 取“好看”的刻度：1/2/2.5/5 × 10^n */
const ticks = computed(() => {
  const all = props.series.flatMap((s) => s.values.map((v) => v ?? 0))
  const max = Math.max(0, ...all),
    min = Math.min(0, ...all)
  const span = max - min || 1
  const raw = span / 4
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) || raw
  const lo = Math.floor(min / step) * step,
    hi = Math.ceil(max / step) * step || step
  const out: number[] = []
  for (let v = lo; v <= hi + step / 2; v += step) out.push(Math.round(v * 1e6) / 1e6)
  return out
})
const lo = computed(() => ticks.value[0])
const hi = computed(() => ticks.value[ticks.value.length - 1])
const y = (v: number) => pad.top + plotH.value * (1 - (v - lo.value) / (hi.value - lo.value || 1))
const band = computed(() => plotW.value / Math.max(1, props.categories.length))
const barW = computed(() => {
  const n = props.series.length
  const inner = band.value * 0.72
  return Math.max(3, Math.min(28, (inner - (n - 1) * 2) / n))
})
const groupW = computed(() => barW.value * props.series.length + (props.series.length - 1) * 2)
const x0 = (i: number) => pad.left + band.value * i + (band.value - groupW.value) / 2

/** 圆角在远离基线的一端，矩形端贴住基线。 */
function barPath(i: number, s: number) {
  const v = props.series[s].values[i]
  if (v == null || v === 0) return ''
  const x = x0(i) + s * (barW.value + 2),
    w = barW.value
  const base = y(0),
    top = y(v)
  const h = Math.abs(base - top)
  const r = Math.min(4, w / 2, h)
  if (v > 0)
    return `M${x},${base}V${top + r}Q${x},${top} ${x + r},${top}H${x + w - r}Q${x + w},${top} ${x + w},${top + r}V${base}Z`
  return `M${x},${base}V${top - r}Q${x},${top} ${x + r},${top}H${x + w - r}Q${x + w},${top} ${x + w},${top - r}V${base}Z`
}
const labelEvery = computed(() => Math.ceil(props.categories.length / Math.max(1, Math.floor(plotW.value / 56))))
const axis = (n: number) => (props.axisFormat ? props.axisFormat(n) : props.format(n))
const tipLeft = computed(() => {
  if (hover.value == null) return 0
  const cx = pad.left + band.value * (hover.value + 0.5)
  return Math.min(Math.max(cx, 90), width.value - 90)
})
</script>
<template>
  <div ref="root" class="gbc">
    <div class="gbc-legend" aria-hidden="true">
      <span v-for="s in series" :key="s.name">
        <i :style="{ background: s.color }"></i>
        {{ s.name }}
      </span>
    </div>
    <svg :width="width" :height="height" role="img" :aria-label="label" @mouseleave="hover = null">
      <g class="gbc-grid">
        <template v-for="t in ticks" :key="t">
          <line :x1="pad.left" :x2="width - pad.right" :y1="y(t)" :y2="y(t)" :class="{ zero: t === 0 }" />
          <text :x="pad.left - 8" :y="y(t)" dy="0.32em" text-anchor="end">{{ axis(t) }}</text>
        </template>
      </g>
      <rect
        v-if="hover != null"
        class="gbc-hover"
        :x="pad.left + band * hover + 2"
        :y="pad.top"
        :width="band - 4"
        :height="plotH"
        rx="4"
      />
      <g v-for="(s, si) in series" :key="s.name">
        <path v-for="(c, i) in categories" :key="c" :d="barPath(i, si)" :fill="s.color" />
      </g>
      <g class="gbc-x">
        <text
          v-for="(c, i) in categories"
          v-show="i % labelEvery === 0"
          :key="c"
          :x="pad.left + band * (i + 0.5)"
          :y="height - 10"
          text-anchor="middle"
        >
          {{ c }}
        </text>
      </g>
      <rect
        v-for="(c, i) in categories"
        :key="`hit-${c}`"
        class="gbc-hit"
        :x="pad.left + band * i"
        :y="pad.top"
        :width="band"
        :height="plotH + pad.bottom"
        @mouseenter="hover = i"
      />
    </svg>
    <div v-if="hover != null" class="gbc-tip" :style="{ left: `${tipLeft}px` }">
      <strong>{{ categories[hover] }}</strong>
      <div v-for="s in series" :key="s.name">
        <i :style="{ background: s.color }"></i>
        <span>{{ s.name }}</span>
        <b>{{ format(s.values[hover]) }}</b>
      </div>
      <slot name="tip" :index="hover" />
    </div>
  </div>
</template>
<style scoped>
.gbc {
  position: relative;
  width: 100%;
}
svg {
  display: block;
}
.gbc-legend {
  display: flex;
  gap: 18px;
  margin-bottom: 8px;
  color: #53645b;
  font-size: 13px;
}
.gbc-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.gbc-legend i,
.gbc-tip i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
}
.gbc-grid line {
  stroke: #edf0eb;
  stroke-width: 1;
}
.gbc-grid line.zero {
  stroke: #c9d1c6;
}
.gbc-grid text,
.gbc-x text {
  fill: #8a958e;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.gbc-hover {
  fill: #f1f5ef;
}
.gbc-hit {
  fill: transparent;
  cursor: default;
}
.gbc-tip {
  position: absolute;
  top: 34px;
  transform: translateX(-50%);
  min-width: 170px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e1e7de;
  border-radius: 8px;
  box-shadow: 0 6px 20px rgb(37 55 49 / 12%);
  color: #253731;
  font-size: 13px;
  pointer-events: none;
}
.gbc-tip strong {
  display: block;
  margin-bottom: 6px;
}
.gbc-tip div {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 1.9;
}
.gbc-tip b {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
</style>
