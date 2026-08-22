<script setup lang="ts">
/**
 * 使用统计仪表盘。读引擎侧 session 历史，画 token 用量趋势。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet } from '@/bridge/http'
import { goBack } from '@/bridge/navigation'
import PageHead from '@/components/PageHead.vue'

const router = useRouter()
const loading = ref(true)
const sessions = ref<Array<{ title: string; date: string; total: number; turns: number }>>([])
const totalTokens = ref(0)
const totalTurns = ref(0)

async function refresh() {
  loading.value = true
  try {
    const data = await apiGet<{ sessions: Array<{ title: string; updated_at: string; usage: { total_tokens: number }; turns: number }> }>('/api/sessions')
    const list = data?.sessions ?? []
    sessions.value = list.map(s => ({
      title: s.title,
      date: s.updated_at,
      total: s.usage?.total_tokens ?? 0,
      turns: s.turns ?? 0,
    })).slice(0, 30)
    totalTokens.value = sessions.value.reduce((sum, s) => sum + s.total, 0)
    totalTurns.value = sessions.value.reduce((sum, s) => sum + s.turns, 0)
  } catch { /* engine not ready */ }
  loading.value = false
}

onMounted(refresh)

const barWidth = computed(() => {
  const max = Math.max(...sessions.value.map(s => s.total), 1)
  return (v: number) => `${(v / max) * 100}%`
})

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}
</script>

<template>
  <div class="page">
    <PageHead title="使用统计" @back="goBack(router, '/')" />
    <main class="body">
      <div v-if="loading" class="empty">加载中…</div>
      <template v-else>
        <section class="cards">
          <div class="card"><span>总 Token</span><strong>{{ fmt(totalTokens) }}</strong></div>
          <div class="card"><span>总会话</span><strong>{{ sessions.length }}</strong></div>
          <div class="card"><span>总轮次</span><strong>{{ totalTurns }}</strong></div>
        </section>
        <p class="sec-label">最近 30 个会话 Token 用量</p>
        <div class="group">
          <div v-for="s in sessions" :key="s.title" class="stat-row">
            <span class="stitle">{{ s.title }}</span>
            <span class="sbar"><span class="sfill" :style="{ width: barWidth(s.total) }" /></span>
            <span class="sval">{{ fmt(s.total) }}</span>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; background: var(--page); }
.body { flex: 1; overflow-y: auto; padding: 10px 12px calc(var(--safe-bottom) + 24px); }
.empty { padding: 30px 10px; text-align: center; font-size: 13px; color: var(--text-3); }
.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.card { display: flex; flex-direction: column; gap: 4px; padding: 12px; border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); }
.card span { font-size: 11px; color: var(--text-3); }
.card strong { font-size: 18px; font-weight: 650; color: var(--text); }
.sec-label { margin: 16px 0 6px; }
.group { border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); overflow: hidden; }
.stat-row { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 8px 12px; }
.stat-row + .stat-row { border-top: 1px solid var(--border); }
.stitle { flex: 1; min-width: 0; font-size: 12px; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sbar { width: 80px; height: 6px; border-radius: 3px; background: var(--fill); overflow: hidden; }
.sfill { display: block; height: 100%; background: var(--blue); border-radius: 3px; }
.sval { flex-shrink: 0; width: 40px; text-align: right; font-size: 11px; color: var(--text-3); }
</style>
