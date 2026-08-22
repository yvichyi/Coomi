<script setup lang="ts">
/**
 * 主聊天窗口。
 *
 * 三件事和别处不一样：
 * 1) 抽屉打开时整个 shell 往右推 + 轻微缩放，是 DeepSeek 的那种层次感；
 * 2) 跟随滚动交给 useAutoScroll，高度变化用 ResizeObserver 兜住 ——
 *    markdown 重排、工具卡展开、软键盘弹出都会改高度，只 watch 数组长度会漏；
 * 3) 连续的工具调用合并成一个 ToolGroup，避免长任务把时间线冲成一堵卡片墙。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { useSessionStore } from '@/stores/session'
import { useSessionsStore } from '@/stores/sessions'
import { useConfigStore } from '@/stores/config'
import { apiGet } from '@/bridge/http'
import { DEMO_PROMPT, isUnattended, shouldAutoplay } from '@/bridge/demoMode'
import { useAutoScroll } from '@/composables/useAutoScroll'
import type { ToolCard } from '@/stores/viewModel'
import { buildTimelineBlocks, type TimelineBlockItem } from '@/utils/chatTimeline'
import type { ApprovalDecision } from '@/protocol/commands'
import TopBar from '@/components/TopBar.vue'
import SideDrawer from '@/components/SideDrawer.vue'
import StatusBar from '@/components/StatusBar.vue'
import Composer from '@/components/Composer.vue'
import EmptyState from '@/components/EmptyState.vue'
import TimelineBlock from '@/components/TimelineBlock.vue'
import LoopProgressBar from '@/components/LoopProgressBar.vue'
import ApprovalSheet from '@/components/ApprovalSheet.vue'
import QuestionSheet from '@/components/QuestionSheet.vue'
import CoomiIcon from '@/components/CoomiIcon.vue'
import { registerOverlay, unregisterOverlay } from '@/bridge/overlayStack'

const session = useSessionStore()
const sessions = useSessionsStore()
const config = useConfigStore()

const scroller = ref<HTMLElement | null>(null)
const virtualScroller = ref<InstanceType<typeof DynamicScroller> | null>(null)
const content = ref<HTMLElement | null>(null)
const drawerOpen = ref(false)
const mood = ref<{ emotion: string; attention: string; bond: number } | null>(null)
let moodTimer: ReturnType<typeof setInterval> | null = null
const focusMode = ref(localStorage.getItem('coomi.focusMode') === 'true')
const bubbleStyle = ref<'bubble' | 'flat' | 'tail'>(localStorage.getItem('coomi.bubbleStyle') as any || 'bubble')
const bgMode = ref<'none' | 'particles' | 'aurora'>(localStorage.getItem('coomi.bgMode') as any || 'none')
const bgCanvas = ref<HTMLCanvasElement | null>(null)
let bgAnimId = 0

function initBgCanvas() {
  const canvas = bgCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const cvs: HTMLCanvasElement = canvas
  const c: CanvasRenderingContext2D = ctx
  const resize = () => {
    cvs.width = canvas!.offsetWidth
    cvs.height = canvas!.offsetHeight
  }
  resize()
  const particles: { x: number; y: number; vx: number; vy: number; r: number; o: number }[] = []
  const w = cvs.width
  const h = cvs.height
  const COUNT = Math.min(50, Math.floor(w * h / 12000))
  for (let i = 0; i < COUNT; i++) {
    particles.push({
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.4,
      vy: (Math.random() - 0.5) * 0.4,
      r: Math.random() * 2 + 1,
      o: Math.random() * 0.4 + 0.1,
    })
  }
  function draw() {
    c.clearRect(0, 0, cvs.width, cvs.height)
    for (const p of particles) {
      p.x += p.vx; p.y += p.vy
      if (p.x < 0 || p.x > cvs.width) p.vx *= -1
      if (p.y < 0 || p.y > cvs.height) p.vy *= -1
      c.beginPath()
      c.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      c.fillStyle = 'rgba(150,180,220,' + p.o + ')'
      c.fill()
    }
    bgAnimId = requestAnimationFrame(draw)
  }
  draw()
  window.addEventListener('resize', resize)
}

async function refreshMood() {
  try {
    const status = await apiGet<{ profile: { emotion: string; attention: string; bond: number } | null }>('/api/cognitive/status')
    mood.value = status?.profile ?? null
  } catch { /* cognitive not installed: mood stays null */ }
}

function toggleFocusMode() {
  focusMode.value = !focusMode.value
  localStorage.setItem('coomi.focusMode', String(focusMode.value))
}

function setBubbleStyle(style: 'bubble' | 'flat' | 'tail') {
  bubbleStyle.value = style
  localStorage.setItem('coomi.bubbleStyle', style)
}

function cycleBgMode() {
  const modes: Array<'none' | 'particles' | 'aurora'> = ['none', 'particles', 'aurora']
  const idx = modes.indexOf(bgMode.value)
  bgMode.value = modes[(idx + 1) % modes.length]
  localStorage.setItem('coomi.bgMode', bgMode.value)
}
/** 全局轮询「后台运行中」状态的定时器（会话列表转圈的数据源）。 */
let runningPoll: ReturnType<typeof setInterval> | null = null

const { following, follow, jumpToBottom } = useAutoScroll(scroller)

const blocks = computed<TimelineBlockItem[]>(() => buildTimelineBlocks(session.timeline))

function syncDigitalLifeMode() {
  config.syncDigitalLifeEnabled()
  if (!session.isBusy) session.setSessionMode(config.digitalLifeEnabled ? 'life' : 'agent')
}

let ro: ResizeObserver | null = null

onMounted(() => {
  session.connect()
  window.addEventListener('coomi:flush-persistence', session.flushPersistence)
  // 记录引擎当前工作目录，会话列表据此把不同项目的会话隔离开。
  void apiGet<{ cwd?: string }>('/api/runtime/health')
    .then(h => { if (h?.cwd) sessions.setCurrentCwd(h.cwd) })
    .catch(() => { /* 引擎未就绪时保持空 cwd，列表退化为全部显示 */ })
  // 以引擎磁盘会话为权威源同步列表，修复“会话记录消失/串会话”。
  void sessions.syncFromEngine()
  if (config.providers.length === 0) void config.fetchProviders()
  // 全局记忆开关以引擎为权威：启动即同步，避免「开关显示关、引擎实际开」的脱节。
  void config.syncGlobalMemoryFromEngine()
  syncDigitalLifeMode()
  window.addEventListener('focus', syncDigitalLifeMode)
  // 全局轮询各会话的「后台运行中」状态：切走会话后任务在引擎侧继续跑，
  // 抽屉/会话页据此显示转圈。轮询常驻（本地 API 开销极小），不依赖抽屉打开。
  void sessions.refreshRunning()
  runningPoll = setInterval(() => sessions.refreshRunning(), 2000)
  void refreshMood()
  moodTimer = setInterval(refreshMood, 5000)
  if (bgMode.value === 'particles') {
    nextTick(initBgCanvas)
  }
  // 高度只要变就重新贴底（内部有 rAF 合并，不怕高频触发）
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => follow())
    if (content.value) ro.observe(content.value)
    if (scroller.value) ro.observe(scroller.value)
  }
  nextTick(follow)
  // 演示模式自动播一轮，省得进来还要先打字才能看见瀑布流。
  if (shouldAutoplay() && session.timeline.length === 0) {
    setTimeout(() => { if (session.timeline.length === 0) session.sendMessage(DEMO_PROMPT) }, 700)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('coomi:flush-persistence', session.flushPersistence)
  window.removeEventListener('focus', syncDigitalLifeMode)
  session.flushPersistence()
  if (runningPoll) { clearInterval(runningPoll); runningPoll = null }
  ro?.disconnect(); ro = null
  if (moodTimer) { clearInterval(moodTimer); moodTimer = null }
  if (bgAnimId) { cancelAnimationFrame(bgAnimId); bgAnimId = 0 }
})

/**
 * 无人值守演示（?demo=1&auto=1）：授权弹层和提问弹层过一会儿自己点掉。
 * 走的是 approve / answerQuestion —— 和真手指按下去完全同一条路，
 * 所以卡片状态、「已回答」气泡都跟着变。截图、录屏、摆着自演都靠它。
 */
if (isUnattended()) {
  const AUTOPILOT_DELAY = 1600
  watch(() => session.pendingApproval?.callId, id => {
    if (!id) return
    setTimeout(() => { if (session.pendingApproval?.callId === id) session.approve(id, 'allow') }, AUTOPILOT_DELAY)
  })
  watch(() => session.pendingQuestion?.callId, id => {
    if (!id) return
    setTimeout(() => {
      const q = session.pendingQuestion
      if (q?.callId === id) {
        session.answerQuestion(id, Object.fromEntries(q.questions.map(question => [question.id, question.options[0]?.label ?? ''])))
      }
    }, AUTOPILOT_DELAY)
  })
}

// ResizeObserver 不可用时的兜底：至少条目增减能跟上。
watch(() => session.timeline.length, () => nextTick(follow))
watch([() => config.digitalLifeEnabled, () => session.isBusy], ([enabled, busy]) => {
  if (!busy) session.setSessionMode(enabled ? 'life' : 'agent')
})

function onDecide(callId: string, decision: ApprovalDecision) { session.approve(callId, decision) }
function onAnswer(callId: string, answers: Record<string, string>) { session.answerQuestion(callId, answers) }
function openDrawer() { drawerOpen.value = true; registerOverlay('side-drawer', closeDrawer) }
function closeDrawer() { drawerOpen.value = false; unregisterOverlay('side-drawer') }

watch(() => session.pendingApproval?.callId, (id, previous) => {
  if (previous) unregisterOverlay(`approval:${previous}`)
  if (id) registerOverlay(`approval:${id}`, () => session.approve(id, 'deny'))
})
watch(() => session.pendingQuestion?.callId, (id, previous) => {
  if (previous) unregisterOverlay(`question:${previous}`)
  if (id) registerOverlay(`question:${id}`, () => session.answerQuestion(id, {}))
})
</script>

<template>
  <div class="chat">
        <canvas v-if="bgMode === 'particles'" ref="bgCanvas" class="bg-particles" />
    <div v-if="bgMode === 'aurora'" class="bg-aurora" />
    <div class="shell" :class="{ pushed: drawerOpen, focus: focusMode, flat: bubbleStyle === 'flat', tail: bubbleStyle === 'tail' }">
      <TopBar @menu="openDrawer">
        <template #extra>
          <button class="icon-btn focus-btn" :class="{ on: focusMode }" aria-label="专注模式" @click="toggleFocusMode">
            <CoomiIcon name="maximize" :size="17" />
          </button>
          <button class="icon-btn" aria-label="气泡样式" @click="setBubbleStyle(bubbleStyle === 'bubble' ? 'flat' : bubbleStyle === 'flat' ? 'tail' : 'bubble')">
            <CoomiIcon name="message" :size="17" />
          </button>
          <button class="icon-btn" :class="{ on: bgMode !== 'none' }" aria-label="动态背景" @click="cycleBgMode">
            <CoomiIcon name="sparkle" :size="17" />
          </button>
        </template>
      </TopBar>

      <main ref="scroller" class="stream">
        <div v-if="session.timeline.length === 0" ref="content" class="inner empty-inner">
          <EmptyState v-if="session.timeline.length === 0" />
        </div>
        <DynamicScroller
          v-else
          ref="virtualScroller"
          :items="blocks"
          key-field="key"
          :min-item-size="48"
          :buffer="640"
          class="virtual-stream"
          page-mode
        >
          <template #default="{ item, index, active }">
            <DynamicScrollerItem
              :item="item"
              :active="active"
              :size-dependencies="[item, item.t === 'one' ? item.item : item.cards]"
              :data-index="index"
              class="virtual-item"
            >
              <TimelineBlock :block="item" />
            </DynamicScrollerItem>
          </template>
        </DynamicScroller>
      </main>

      <Transition name="pop">
        <button v-if="!following" class="to-bottom" aria-label="回到底部" @click="jumpToBottom">
          <CoomiIcon name="arrowDown" :size="18" />
        </button>
      </Transition>

      <LoopProgressBar v-if="session.loop.active" :loop="session.loop" />
      <div v-if="session.retryConfirmation" class="retry-confirm">
        <div><CoomiIcon name="alert" :size="16" /><span>{{ session.retryConfirmation }}</span></div>
        <div class="retry-actions">
          <button class="retry-secondary" @click="session.dismissRetry()">结束任务</button>
          <button class="retry-primary" @click="session.retryInterruptedTurn()">继续重试</button>
        </div>
      </div>
      <StatusBar />
      <div v-if="mood" class="mood-bar">
        <span class="mood-item"><CoomiIcon name="heart" :size="13" />{{ mood.emotion }}</span>
        <span class="mood-item"><CoomiIcon name="eye" :size="13" />{{ mood.attention }}</span>
        <span class="mood-item bond"><CoomiIcon name="link" :size="13" />{{ Math.round(mood.bond * 100) }}%</span>
      </div>
      <Composer />
    </div>

    <SideDrawer :open="drawerOpen" @close="closeDrawer" />

    <ApprovalSheet
      v-if="session.pendingApproval"
      :card="session.pendingApproval"
      @decide="(d: ApprovalDecision) => onDecide(session.pendingApproval!.callId, d)"
    />
    <QuestionSheet
      v-else-if="session.pendingQuestion"
      :card="session.pendingQuestion"
      @answer="(answers: Record<string, string>) => onAnswer(session.pendingQuestion!.callId, answers)"
    />
  </div>
</template>

<style scoped>
.chat {
  height: 100%;
  min-height: 0;
  background-color: transparent;
  background-image:
    linear-gradient(var(--chat-background-overlay), var(--chat-background-overlay)),
    var(--chat-background-image);
  background-position: center;
  background-size: cover;
  position: relative;
  overflow: hidden;
}
.bg-particles {
  position: absolute; inset: 0; z-index: 0; pointer-events: none;
}
.bg-aurora {
  position: absolute; inset: 0; z-index: 0; pointer-events: none;
  background: linear-gradient(135deg, var(--blue) 0%, var(--ok) 25%, var(--orange) 50%, var(--blue) 75%, var(--ok) 100%);
  background-size: 400% 400%;
  opacity: 0.06;
  animation: aurora-shift 18s ease-in-out infinite;
  mix-blend-mode: screen;
}
@keyframes aurora-shift {
  0%, 100% { background-position: 0% 50%; }
  25% { background-position: 100% 50%; }
  50% { background-position: 50% 100%; }
  75% { background-position: 50% 0%; }
}
.shell { position: relative; z-index: 1; }

.shell {
  position: relative;
  display: flex; flex-direction: column; height: 100%; min-height: 0;
  background: transparent;
  transform-origin: left center;
  /* 只保留 transform 动画：Android WebView 里 transform+border-radius 同时
     过渡会反复重建合成层，表现为打开侧边栏时主内容文字闪烁。
     will-change 让合成层常驻，避免动画开始/结束时闪一下。 */
  transition: transform .3s cubic-bezier(.22, .68, .19, 1);
  will-change: transform;
}
.shell.pushed {
  /* origin 为 left center 时，scale(.94) 使右边缘内缩 6%；
     translateX(6%) 精确抵消，保证右侧始终贴住屏幕右缘（不会右侧被裁）。 */
  transform: translateX(6%) scale(.94);
  border-radius: 20px;
  overflow: hidden;
}

.stream {
  flex: 1; min-width: 0; min-height: 0; max-width: 100%; overflow-x: hidden; overflow-y: auto;
  -webkit-overflow-scrolling: touch; overscroll-behavior-y: contain;
}
.inner {
  display: flex; flex-direction: column; gap: 12px;
  width: 100%; min-width: 0; min-height: 100%; padding: 10px 12px 18px; overflow-x: hidden;
}
.empty-inner { min-height: 100%; }
.icon-btn { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 8px; color: var(--text-2); }
.icon-btn.on { background: var(--blue-soft); color: var(--blue); }
.icon-btn:active { background: var(--fill); }
.shell.focus :deep(.top-bar),
.shell.focus :deep(.status-bar),
.shell.focus :deep(.mood-bar) { display: none; }
.shell.focus .stream { padding-top: 10px; }
.shell.flat :deep(.bubble) { border-radius: 12px; }
.shell.tail :deep(.bubble) { border-radius: 12px 12px 0 12px; }
.mood-bar { display: flex; gap: 12px; padding: 4px 16px; background: var(--bg); border-top: 1px solid var(--border); }
.mood-item { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: var(--text-3); }
.mood-item.bond { margin-left: auto; }
.virtual-stream { width: 100%; min-width: 0; padding: 10px 12px 18px; overflow: visible; }
.virtual-item { width: 100%; min-width: 0; padding-bottom: 12px; }

.to-bottom {
  position: absolute; left: 50%; bottom: 116px; z-index: 8;
  display: grid; place-items: center;
  width: 38px; height: 38px; margin-left: -19px;
  border: 1px solid var(--border); border-radius: 50%;
  background: var(--bg); color: var(--text-2);
  box-shadow: var(--shadow-2);
}
.to-bottom:active { background: var(--fill); }
.pop-enter-active, .pop-leave-active { transition: opacity .18s ease, transform .18s ease; }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(8px) scale(.9); }

.retry-confirm { margin: 0 12px 6px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); box-shadow: var(--shadow-1); }
.retry-confirm > div:first-child { display: flex; align-items: center; gap: 7px; font-size: 13px; color: var(--text-2); }
.retry-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 9px; }
.retry-actions button { min-height: 34px; padding: 0 13px; border-radius: 6px; font-size: 13px; font-weight: 600; }
.retry-secondary { background: var(--fill); color: var(--text-2); }
.retry-primary { background: var(--blue); color: #fff; }
</style>
