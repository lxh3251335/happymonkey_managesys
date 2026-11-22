import { ref } from 'vue'
import type { Router } from 'vue-router'

/**
 * 从顶栏（搜索、粘贴登记）跳到某个页面时，页面可能就是当前页，只是地址里的筛选不同。
 * 页面只在打开时读地址里的筛选，所以这时让它重新打开一次：布局里的页面以 path + 这个计数作 key。
 */
export const reopenCount = ref(0)

export async function openFromTopbar(router: Router, link: string) {
  await router.push(link)
  reopenCount.value++
}
