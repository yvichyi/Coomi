<script setup lang="ts">
/**
 * 定时任务：让 Agent 按 cron（分 时）自动执行任务。
 * 例：cron "30 8" prompt "看看今天的科技资讯，总结发我" = 每天 08:30 自动跑。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet, apiSend } from '@/bridge/http'
import { goBack } from '@/bridge/navigation'
import PageHead from '@/components/PageHead.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'

interface ScheduleEntry {
  id: string
  cron: string
  prompt: string
  notify: boolean
  enabled: boolean
  created_at: string
}
interface ScheduleRun {
  id: string
  schedule_id: string
  started_at: string
  finished_at: string
  status: string
  summary: string
}

const router = useRouter()
const schedules = ref<ScheduleEntry[]>([])
const runs = ref<ScheduleRun[]>([])
const wantNew = ref(false)
const formCron = ref('30 8')
const formPrompt = ref('')
const formNotify = ref(true)
const formError = ref('')
const busy = ref(false)
let poll: ReturnType<typeof setInterval> | null = null

const CHECK_ICON = 'check'
const CLOCK_ICON = 'clock'

async function refresh() {
  try {
    const data = await apiGet<{ schedules: ScheduleEntry[]; runs: ScheduleRun[] }>('/api/schedules')
    schedules.value = data.schedules ?? []
    runs.value = data.runs ?? []
    // 新完成的任务推送系统通知
    const done = runs.value.find(r => r.status === 'completed' || r.status === 'failed')
    if (done && window.CoomiAndroid?.notify) {
      window.CoomiAndroid.notify(
        done.status === 'completed' ? '定时任务完成' : '定时任务失败',
        done.summary.slice(0, 80),
      )
    }
  } catch { /* engine not ready */ }
}

async function save() {
  if (busy.value) return
  busy.value = true
  formError.value = ''
  try {
    await apiSend('/api/schedules', 'POST', {
      cron: formCron.value.trim(),
      prompt: formPrompt.value.trim(),
      notify: formNotify.value,
      enabled: true,
    })
    formPrompt.value = ''
    wantNew.value = false
    await refresh()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : String(error)
  } finally {
    busy.value = false
  }
}

async function toggle(s: ScheduleEntry) {
  try {
    await apiSend('/api/schedules', 'POST', { ...s, enabled: !s.enabled })
    await refresh()
  } catch { /* ignore */ }
}

async function runNow(s: ScheduleEntry) {
  try {
    await apiSend(`/api/schedules/${s.id}`, 'POST')
  } catch { /* ignore */ }
}

async function remove(s: ScheduleEntry) {
  try {
    await apiSend(`/api/schedules/${s.id}`, 'DELETE')
    await refresh()
  } catch { /* ignore */ }
}

function preset(prompt: string, cron: string) {
  formPrompt.value = prompt
  formCron.value = cron
  wantNew.value = true
}

function fmtTime(ts: string): string {
  return ts.replace('T', ' ').slice(5, 16)
}

onMounted(() => {
  void refresh()
  poll = setInterval(refresh, 10000)
})
onBeforeUnmount(() => { if (poll) clearInterval(poll) })
</script>

<template>
  <div class="page">
    <PageHead title="定时任务" @back="goBack(router, '/')">
      <template #right>
        <button class="icon-btn blue" aria-label="新建" @click="wantNew = !wantNew">
          <CoomiIcon name="plus" :size="18" />
        </button>
      </template>
    </PageHead>

    <main class="body">
      <!-- 新建/编辑 -->
      <div v-if="wantNew" class="form">
        <p class="sec-label">新建定时任务</p>
        <div class="group form-group">
          <label><span>执行时间（cron：分 时）</span><input v-model="formCron" placeholder="30 8 = 每天 08:30" /></label>
          <label><span>任务内容（发给 Agent 的指令）</span><textarea v-model="formPrompt" rows="3" placeholder="例：查看今天的 HackerNews 头条并总结" /></label>
          <label class="check"><input v-model="formNotify" type="checkbox" /> 完成后通知我</label>
          <p v-if="formError" class="err">{{ formError }}</p>
          <div class="actions">
            <button class="primary" :disabled="busy || !formPrompt.trim()" @click="save">保存</button>
          </div>
        </div>
      </div>

      <!-- 预设 -->
      <p class="sec-label">常用预设</p>
      <div class="presets">
        <button @click="preset('查看今天的科技日报，用中文总结 5 条最重要的新闻', '30 8')">📰 早 8:30 科技日报</button>
        <button @click="preset('检查我的工作目录，把未提交的改动用 git status 汇总给我', '0 9')">🔍 早 9:00 工作汇报</button>
        <button @click="preset('提醒我喝水（简短即可）', '* 14')">💧 下午每小时提示</button>
      </div>

      <!-- 任务列表 -->
      <p class="sec-label">我的任务</p>
      <div v-if="schedules.length === 0" class="empty">还没有定时任务。点右上角 ＋ 新建，或用一个预设。</div>
      <div v-else class="group">
        <div v-for="s in schedules" :key="s.id" class="row">
          <button class="main" @click="runNow(s)">
            <span class="rtitle">
              <CoomiIcon name="clock" :size="14" class="pin" />
              <b>每天 {{ s.cron.replace(' ', ':') }}</b>
            </span>
            <span class="rprompt">{{ s.prompt }}</span>
          </button>
          <button class="sw" :class="{ on: s.enabled }" role="switch" :aria-checked="s.enabled" @click="toggle(s)"></button>
          <button class="icon danger" aria-label="删除" @click="remove(s)"><CoomiIcon name="trash" :size="16" /></button>
        </div>
      </div>

      <!-- 执行记录 -->
      <template v-if="runs.length">
        <p class="sec-label">最近执行</p>
        <div class="group">
          <div v-for="r in runs.slice(0, 5)" :key="r.id" class="run-row">
            <span class="dot" :class="r.status" />
            <div class="rinfo">
              <b>{{ r.status === 'completed' ? '完成' : r.status === 'failed' ? '失败' : '执行中' }} · {{ fmtTime(r.started_at) }}</b>
              <p>{{ r.summary }}</p>
            </div>
            <CoomiIcon v-if="r.status === 'completed'" name="check" :size="15" class="ok" />
            <CoomiIcon v-else-if="r.status === 'failed'" name="alert" :size="15" class="bad" />
          </div>
        </div>
      </template>

      <p class="note">定时任务在引擎后台运行，不依赖 App 保持在前台。cron 格式：两位数字，「分 时」，* 表示任意。例：<code>30 8</code> = 每天 08:30。</p>
    </main>
  </div>
</template>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; background: var(--page); }
.icon-btn.blue { color: var(--blue); }
.body { flex: 1; overflow-y: auto; padding: 10px 12px calc(var(--safe-bottom) + 24px); }
.sec-label { margin: 16px 0 6px; }
.group { border-radius: var(--r-card); background: var(--bg); box-shadow: var(--shadow-1); overflow: hidden; }
.form-group { padding: 14px; }
.form-group label { display: block; margin-bottom: 12px; font-size: 12.5px; color: var(--text-2); }
.form-group label > span { display: block; margin-bottom: 5px; }
.form-group input, .form-group textarea { width: 100%; border: 1px solid var(--border-strong); border-radius: 8px; background: var(--page); color: var(--text); padding: 9px 10px; font-size: 14px; }
.form-group textarea { resize: vertical; }
.form-group .check { display: flex; align-items: center; gap: 8px; }
.check input { width: auto; }
.err { color: var(--danger); font-size: 12px; margin: 6px 0; }
.actions { display: flex; justify-content: flex-end; }
.primary { min-height: 38px; padding: 0 18px; border-radius: 8px; border: 0; background: var(--blue); color: #fff; font-size: 14px; font-weight: 600; }
.primary:disabled { opacity: .5; }
.presets { display: flex; flex-direction: column; gap: 7px; }
.presets button { min-height: 42px; padding: 0 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--text); font-size: 13px; text-align: left; }
.presets button:active { background: var(--fill); }
.empty { padding: 24px 14px; text-align: center; font-size: 13px; color: var(--text-3); }
.row { display: flex; align-items: center; gap: 8px; padding: 10px 12px; }
.row + .row { border-top: 1px solid var(--border); }
.main { flex: 1; min-width: 0; text-align: left; }
.rtitle { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
.rtitle b { color: var(--blue); font-size: 13.5px; }
.pin { color: var(--blue); }
.rprompt { display: block; color: var(--text-2); font-size: 12.5px; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.sw { position: relative; flex-shrink: 0; width: 44px; height: 26px; border-radius: 13px; background: var(--border-strong); border: 0; transition: background .2s; }
.sw::after { content: ""; position: absolute; top: 2.5px; left: 2.5px; width: 21px; height: 21px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.12); transition: transform .2s; }
.sw.on { background: var(--blue); }
.sw.on::after { transform: translateX(18px); }
.icon.danger { color: var(--danger); }
.run-row { display: flex; gap: 10px; padding: 10px 12px; align-items: flex-start; }
.run-row + .run-row { border-top: 1px solid var(--border); }
.dot { flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; }
.dot.completed { background: var(--ok); }
.dot.failed { background: var(--danger); }
.dot.running { background: var(--blue); animation: pulse 1s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .3; } }
.rinfo { flex: 1; min-width: 0; }
.rinfo b { font-size: 12.5px; color: var(--text); }
.rinfo p { margin: 3px 0 0; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.ok { color: var(--ok); flex-shrink: 0; margin-top: 4px; }
.bad { color: var(--danger); flex-shrink: 0; margin-top: 4px; }
.note { margin-top: 14px; padding: 0 4px; font-size: 11.5px; line-height: 1.75; color: var(--text-3); }
.note code { font-family: var(--font-mono); font-size: 10.8px; }
</style>