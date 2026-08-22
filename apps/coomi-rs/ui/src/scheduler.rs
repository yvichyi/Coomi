//! 定时任务调度器：按 cron 表达式在后台触发 Agent 任务。
//!
//! 任务定义存 `~/.coomi/config/schedules.json`（引擎 home 下），AppState 启动时加载，
//! 引擎内部每 30 秒扫描一次，到点 spawn 一个后台执行（无 WS 连接，事件缓存在
//! SessionTask 队列供 /api/tasks 查阅），结果写入最近执行记录，前端可轮询展示或推送通知。

use anyhow::Result;
use chrono::{DateTime, Local};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::Mutex;

pub const SCHEDULES_FILE: &str = "config/schedules.json";

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ScheduleEntry {
    pub id: String,
    /// cron 风格：分 时。例："30 8" = 每天 08:30。
    pub cron: String,
    pub prompt: String,
    /// 触发完成后是否推送系统通知（前端轮询到新执行记录后调 CoomiAndroid.notify）。
    #[serde(default = "default_true")]
    pub notify: bool,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub created_at: String,
}

fn default_true() -> bool {
    true
}

impl ScheduleEntry {
    pub fn matches_now(&self, now: DateTime<Local>) -> bool {
        let parts: Vec<&str> = self.cron.split_whitespace().collect();
        if parts.len() != 2 {
            return false;
        }
        let minute_ok = parts[0]
            .parse::<u32>()
            .map(|m| m == now.minute())
            .unwrap_or(parts[0] == "*");
        let hour_ok = parts[1]
            .parse::<u32>()
            .map(|h| h == now.hour())
            .unwrap_or(parts[1] == "*");
        minute_ok && hour_ok
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ScheduleRun {
    pub id: String,
    pub schedule_id: String,
    pub started_at: String,
    pub finished_at: String,
    pub status: String,
    #[serde(default)]
    pub summary: String,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ScheduleStore {
    pub schedules: Vec<ScheduleEntry>,
    /// 最近 200 条执行记录。
    pub runs: Vec<ScheduleRun>,
}

impl ScheduleStore {
    pub fn load(home: &Path) -> Self {
        let path = home.join(SCHEDULES_FILE);
        match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_default(),
            Err(_) => Self::default(),
        }
    }

    pub fn save(&self, home: &Path) -> Result<()> {
        let path = home.join(SCHEDULES_FILE);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let json = serde_json::to_string_pretty(self)?;
        fs::write(&path, json)?;
        Ok(())
    }

    pub fn upsert(&mut self, entry: ScheduleEntry) -> String {
        match self.schedules.iter_mut().find(|s| s.id == entry.id) {
            Some(existing) => {
                *existing = entry.clone();
                entry.id
            }
            None => {
                self.schedules.push(entry.clone());
                entry.id
            }
        }
    }

    pub fn remove(&mut self, id: &str) -> bool {
        let before = self.schedules.len();
        self.schedules.retain(|s| s.id != id);
        self.schedules.len() != before
    }

    pub fn record_run(&mut self, run: ScheduleRun) {
        self.runs.push(run);
        if self.runs.len() > 200 {
            self.runs.drain(0..(self.runs.len() - 200));
        }
    }
}

/// 调度器运行时状态：每 30 秒扫描一次，靠 last_trigger_key 防止同一分钟重复触发。
pub struct Scheduler {
    pub store: Arc<Mutex<ScheduleStore>>,
    pub last_trigger_key: Arc<Mutex<Option<String>>>,
}

impl Scheduler {
    pub fn new(home: &Path) -> Arc<Self> {
        Arc::new(Self {
            store: Arc::new(Mutex::new(ScheduleStore::load(home))),
            last_trigger_key: Arc::new(Mutex::new(None)),
        })
    }
}

/// 扫描当期：命中 cron 且本分钟未触发过，返回对应条目。
pub async fn next_due(scheduler: &Scheduler, now: DateTime<Local>) -> Option<ScheduleEntry> {
    let store = scheduler.store.lock().await;
    if store.schedules.is_empty() {
        return None;
    }
    let key = format!("{}:{}", now.format("%F"), now.format("%H:%M"));
    let mut last = scheduler.last_trigger_key.lock().await;
    if last.as_deref() == Some(key.as_str()) {
        return None;
    }
    for entry in store.schedules.iter() {
        if entry.enabled && entry.matches_now(now) {
            *last = Some(key);
            return Some(entry.clone());
        }
    }
    None
}

pub fn now_string() -> String {
    chrono::Local::now()
        .format("%Y-%m-%d %H:%M:%S")
        .to_string()
}