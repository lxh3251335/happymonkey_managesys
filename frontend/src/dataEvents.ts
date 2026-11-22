import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 页面之外登记了数据（顶栏的“粘贴登记”）时通知正在看的页面重新加载。
 * 发出：`notifyDataChanged()`；页面里：`onDataChanged(load)`。
 */
const EVENT = 'commerce:data-changed'

export function notifyDataChanged() {
  window.dispatchEvent(new Event(EVENT))
}

export function onDataChanged(reload: () => unknown) {
  const handler = () => void reload()
  onMounted(() => window.addEventListener(EVENT, handler))
  onBeforeUnmount(() => window.removeEventListener(EVENT, handler))
}
