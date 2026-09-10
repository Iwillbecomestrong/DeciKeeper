
    // 全局应用状态（模拟 Android SharedPreferences 与业务单例）
    const appState = {

      
      statsPeriod: 'week',
      alarmQuizStep: 0,

      appWindowRules: [
        { id: 1, name: '晚间娱乐', start: '19:30', end: '21:00', days: [0,1,2,3,4,5,6], apps: ['com.bilibili.app.in', 'com.ss.android.ugc.aweme'], enabled: true },
        { id: 2, name: '工作勿扰', start: '09:00', end: '11:30', days: [1,2,3,4,5], apps: ['com.tencent.mm'], enabled: false }
      ],
      editingRuleId: null,
      previewTab: 'app',

      inAdaptation: true,
      snoozeConfig: '10分钟/2次',
      keepAlive: true,
      challengeConfig: '数学2道/诗词3句',
      currentScreen: 'home',
      // 无障碍守护与加强约束全量状态 (对应 fragment_rules.xml)
      a11yEnabled: true,
      pendingA11yDisable: false,
      antiUninstallEnabled: true,
      pendingAntiUninstallDisable: false,
      antiRevokePermEnabled: true,
      pendingAntiRevokeDisable: false,
      privacySpaceGuardEnabled: true,
      pendingPrivacyGuardDisable: false,
      alarmPowerOffProtection: true,
      hideInRecentTasks: false,

      // 离床错题收藏夹状态 (对应 activity_bookmark.xml)
      bookmarkTab: 'poems', // 'poems' | 'words' | 'clozes'
      bookmarkSortBy: 'skip', // 'skip' | 'time'
      bookmarkRange: 'all', // 'all' | '7d' | '30d' | '90d'
      bookmarkSearch: '',
      bookmarks: {
        poems: [
          { id: 1, title: '将进酒', author: '李白 · 唐', content: '人生得意须尽欢，莫使金樽空对月。天生我材必有用，千金散尽还复来。', skipCount: 5, date: '2026-09-02' },
          { id: 2, title: '定风波 · 莫听穿林打叶声', author: '苏轼 · 宋', content: '竹杖芒鞋轻胜马，谁怕？一蓑烟雨任平生。料峭春风吹酒醒，微冷，山头斜照却相迎。', skipCount: 3, date: '2026-08-28' },
          { id: 3, title: '登高', author: '杜甫 · 唐', content: '无边落木萧萧下，不尽长江滚滚来。万里悲秋常作客，百年多病独登台。', skipCount: 2, date: '2026-09-04' }
        ],
        words: [
          { id: 101, word: 'procrastinate', phonetic: '/prəʊˈkræstɪneɪt/', translation: 'v. 拖延；耽搁；迟延', skipCount: 6, date: '2026-09-01' },
          { id: 102, word: 'resilience', phonetic: '/rɪˈzɪliəns/', translation: 'n. 恢复力；弹力；顺应力', skipCount: 4, date: '2026-08-30' },
          { id: 103, word: 'perseverance', phonetic: '/ˌpɜːsɪˈvɪərəns/', translation: 'n. 坚持不懈；毅力；百折不挠', skipCount: 2, date: '2026-09-03' }
        ],
        clozes: [
          { id: 201, sentence: 'Consistent self-discipline builds true [____] over impulsive desires.', answer: 'freedom', options: ['freedom', 'prison', 'boredom', 'delay'], skipCount: 4, date: '2026-08-29' },
          { id: 202, sentence: 'The early morning routine helps cultivate mental [____] for the day.', answer: 'clarity', options: ['clarity', 'fatigue', 'chaos', 'sorrow'], skipCount: 3, date: '2026-09-02' }
        ]
      },

      // 黑白名单状态
      listMode: 'blacklist', // 'blacklist' | 'whitelist'
      selectedApps: ['com.ss.android.ugc.aweme', 'com.smile.gifmaker', 'com.bilibili.app.in', 'com.miHoYo.Yuanshen'],
      // 闹钟列表
      alarms: [
        { id: 1, time: '07:00', label: '晨起自习', repeat: '周一至周五', challenge: `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"></path><circle cx="12" cy="13" r="4"></circle></svg>浴室远处二维码`, antiRelapse: true, enabled: true }
      ],
      // 全局参数
      baselineHour: '08:00',
      maxBorrowRatio: '50%',
      weeklyPrivilegeDuration: 120,
      weeklyPrivilegeCount: 2,
      // 额度
      remainingMinutes: 25,
      remainingCount: 2.0,
      borrowedMinutes: 5,
      borrowedCount: 0.5
    };

    function showToast(msg) {
      const toast = document.getElementById('toast');
      toast.innerText = msg;
      toast.classList.remove('opacity-0');
      setTimeout(() => toast.classList.add('opacity-0'), 2000);
    }

    // 核心导航路由
    
    function showPrivilegeConfirmModal() {
      const modal = document.createElement('div');
      modal.className = 'fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4 backdrop-blur-sm';
      modal.innerHTML = `
        <div class="bg-white rounded-3xl w-full max-w-sm overflow-hidden shadow-2xl">
          <div class="bg-[#1a3830] p-6 text-center">
            <div class="w-16 h-16 bg-[#2d5f4e] rounded-full mx-auto flex items-center justify-center text-3xl mb-3">💎</div>
            <h3 class="text-xl font-bold text-white">使用每周特权</h3>
            <p class="text-[#8fb3a4] text-xs mt-2">越过双锁，畅享不受限的自由时光</p>
          </div>
          <div class="p-6 space-y-4 text-sm text-[#4e665d]">
            <div class="flex justify-between items-center py-2 border-b border-[#edf3f0]">
              <span>获得时长</span>
              <span class="font-bold text-[#1b5c45]">120 分钟</span>
            </div>
            <div class="flex justify-between items-center py-2 border-b border-[#edf3f0]">
              <span>本周剩余特权</span>
              <span class="font-bold text-[#E65100]">1 / 2 次</span>
            </div>
            <p class="text-xs text-[#E65100] bg-[#FFF3E0] p-3 rounded-xl border border-[#FFE0B2]">
              ⚠️ 警告：特权一旦激活，在这 120 分钟内决定守护将完全放行所有操作，请三思而后行。
            </p>
          </div>
          <div class="flex p-4 gap-3 bg-[#f8fbf9]">
            <button onclick="this.closest('.fixed').remove()" class="flex-1 py-3 bg-[#f0f6f3] text-[#1a3830] font-bold rounded-2xl">暂不使用</button>
            <button onclick="this.closest('.fixed').remove(); showToast('特权已生效！'); document.getElementById('privilege-card').style.opacity = '0.5';" class="flex-1 py-3 bg-[#1a3830] text-white font-bold rounded-2xl">确认启用</button>
          </div>
        </div>
      `;
      document.body.appendChild(modal);
    }

    function showBatchCycleModal() {
      const modal = document.createElement('div');
      modal.className = 'fixed inset-0 bg-black/60 z-50 flex items-end sm:items-center justify-center sm:p-4 transition-opacity';
      modal.innerHTML = `
        <div class="bg-white w-full sm:max-w-sm sm:rounded-3xl rounded-t-3xl overflow-hidden shadow-2xl">
          <div class="p-5 border-b border-[#EEEEEE] flex justify-between items-center">
            <span class="font-bold text-lg text-[#333333]">批量设置周期</span>
            <button onclick="this.closest('.fixed').remove()" class="text-[#999999] text-xl">&times;</button>
          </div>
          <div class="p-5 space-y-4">
            <div class="flex flex-col gap-2">
              <label class="flex items-center gap-3 p-3 bg-[#F5F5F5] rounded-xl">
                <input type="checkbox" class="w-5 h-5 accent-[#1b5c45]" checked>
                <span class="font-bold text-[#333333]">晚间娱乐</span>
              </label>
              <label class="flex items-center gap-3 p-3 bg-[#F5F5F5] rounded-xl">
                <input type="checkbox" class="w-5 h-5 accent-[#1b5c45]" checked>
                <span class="font-bold text-[#333333]">工作勿扰</span>
              </label>
            </div>
            
            <div class="pt-4 border-t border-[#EEEEEE]">
              <div class="text-sm font-bold text-[#333333] mb-3">统一设为：</div>
              <div class="flex gap-2">
                <button class="flex-1 py-2 bg-[#EDE7F6] text-[#5E35B1] font-bold rounded-lg border border-[#D1C4E9]">工作日</button>
                <button class="flex-1 py-2 bg-[#F5F5F5] text-[#666666] font-bold rounded-lg border border-[#E0E0E0]">周末</button>
                <button class="flex-1 py-2 bg-[#F5F5F5] text-[#666666] font-bold rounded-lg border border-[#E0E0E0]">每天</button>
              </div>
            </div>
            <button onclick="this.closest('.fixed').remove(); showToast('批量设置已保存');" class="w-full mt-4 py-3 bg-[#1a3830] text-white font-bold rounded-xl shadow-md">应用更改</button>
          </div>
        </div>
      `;
      document.body.appendChild(modal);
    }
    
    function showMultiplierModal() {
      const modal = document.createElement('div');
      modal.className = 'fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4 backdrop-blur-sm';
      modal.innerHTML = `
        <div class="bg-white rounded-3xl w-full max-w-sm p-6 shadow-2xl relative">
          <button onclick="this.closest('.fixed').remove()" class="absolute top-4 right-4 text-[#999] text-xl">&times;</button>
          <h3 class="text-lg font-bold text-[#163027] mb-2">⚖️ 加权计算规则</h3>
          <p class="text-sm text-[#5c736a] mb-4">决定守护系统旨在培养长期自律，因此对“冲动行为”有惩罚性的加权。</p>
          <div class="space-y-2 text-xs">
            <div class="flex justify-between items-center p-3 bg-[#f4f8f6] rounded-xl">
              <span class="font-bold text-[#163027]">正常申请</span>
              <span class="text-[#1b5c45]">1 倍 (原价)</span>
            </div>
            <div class="flex justify-between items-center p-3 bg-[#fff0e6] border border-[#ffccbc] rounded-xl">
              <span class="font-bold text-[#e64a19]">冷静期内强行申请</span>
              <span class="text-[#d84315] font-bold">2 倍 (惩罚加权)</span>
            </div>
            <div class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-xl">
              <span class="font-bold text-[#666]">每周特权</span>
              <span class="text-[#666]">不计入总耗时</span>
            </div>
          </div>
        </div>
      `;
      document.body.appendChild(modal);
    }
    
    function handleAlarmQuizAnswer(isCorrect, btn) {
      if (!isCorrect) {
        btn.classList.remove('border-[#dce8e2]', 'text-[#5c736a]');
        btn.classList.add('border-red-500', 'bg-red-50', 'text-red-700');
        btn.style.transform = 'translateX(-5px)';
        setTimeout(() => btn.style.transform = 'translateX(5px)', 100);
        setTimeout(() => btn.style.transform = 'translateX(0)', 200);
        showToast('回答错误，请再想想');
        return;
      }
      
      appState.alarmQuizStep++;
      if (appState.alarmQuizStep >= 2) {
        showToast('挑战成功！');
        document.getElementById('alarm-quiz-modal').remove();
        appState.alarmQuizStep = 0;
      } else {
        // Change question to step 2
        document.getElementById('alarm-quiz-title').innerText = '数学心算 2/2';
        document.getElementById('alarm-quiz-content').innerHTML = `
          <div class="text-lg font-bold text-center text-[#163027] py-6">(14 × 6) + 23 = ?</div>
          <div class="space-y-1.5 pt-1">
            <button onclick="handleAlarmQuizAnswer(false, this)" class="w-full py-2 bg-white border border-[#dce8e2] rounded-2xl text-xs font-medium text-[#5c736a] text-left px-3 transition-colors">A. 97</button>
            <button onclick="handleAlarmQuizAnswer(true, this)" class="w-full py-2 bg-white border border-[#dce8e2] rounded-2xl text-xs font-medium text-[#5c736a] text-left px-3 transition-colors">B. 107</button>
            <button onclick="handleAlarmQuizAnswer(false, this)" class="w-full py-2 bg-white border border-[#dce8e2] rounded-2xl text-xs font-medium text-[#5c736a] text-left px-3 transition-colors">C. 117</button>
          </div>
        `;
      }
    }


    function navTo(screenId) {
      appState.currentScreen = screenId;
      renderCurrentScreen();
      updateNavHighlight(screenId);
      updateExplanationCard(screenId);
    }

    function updateNavHighlight(screenId) {
      // 外部顶部按钮高亮
      document.querySelectorAll('.nav-btn, header button').forEach(btn => {
        btn.classList.remove('bg-[#1a3830]', 'text-white');
        btn.classList.add('text-[#5c736a]');
      });
      const topBtn = document.getElementById('btn-nav-' + screenId);
      if (topBtn) {
        topBtn.classList.add('bg-[#1a3830]', 'text-white');
        topBtn.classList.remove('text-[#5c736a]');
      }

      // 手机底部导航显隐与选中
      const phoneNav = document.getElementById('phone-nav');
      const isSubOrLock = screenId.startsWith('sub_') || screenId.startsWith('lock_');
      if (isSubOrLock) {
        phoneNav.classList.add('hidden');
      } else {
        phoneNav.classList.remove('hidden');
        document.querySelectorAll('.nav-bar-item').forEach(item => {
          if (item.getAttribute('data-nav') === screenId) {
            item.classList.add('text-[#1a3830]');
            item.classList.remove('text-[#738a82]');
          } else {
            item.classList.remove('text-[#1a3830]');
            item.classList.add('text-[#738a82]');
          }
        });
      }
    }

    // 视图渲染引擎
    function renderCurrentScreen() {
      const content = document.getElementById('screen-content');
      content.scrollTop = 0;
      content.classList.remove('animate-fade-in');
      void content.offsetWidth; // trigger reflow
      content.classList.add('animate-fade-in');

      switch(appState.currentScreen) {
        
        // 1. 主页 (真正砍掉繁杂的本段/结余明细，纯白清爽)
        case 'home':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <!-- 适应期提示 -->
              ${appState.inAdaptation ? `
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-2.5 flex justify-between items-center shadow-2xs">
                <div>
                  <span class="font-bold text-[#163027] text-xs">✨ 当前处于适应期</span>
                  <p class="text-[9px] text-[#5c736a]">设置立即生效，24小时后进入严格模式</p>
                </div>
                <button onclick="endAdaptationPeriod()" class="text-[9px] px-2.5 py-1 bg-[#eaf3ef] text-[#1b5c45] font-bold rounded-full hover:bg-[#dbeee5]">结束适应</button>
              </div>` : ''}
                <button onclick="confirmEndAdaptation()" class="text-[9px] px-2.5 py-1 bg-[#eaf3ef] text-[#1b5c45] font-bold rounded-full hover:bg-[#dbeee5]">结束适应</button>
              </div>

              <!-- 权限健康卡片 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs space-y-1.5">
                <div class="flex justify-between items-center text-xs">
                  <span class="font-bold text-[#163027]">服务状态：${appState.a11yEnabled ? '已激活守护' : '<span class="text-rose-600">未受保护</span>'}</span>
                  <span class="w-2 h-2 rounded-full ${appState.a11yEnabled ? 'bg-[#1b5c45]' : 'bg-rose-500'}"></span>
                </div>
                <div class="text-[10px] text-[#5c736a] flex justify-between">
                  <span>• 无障碍：${appState.a11yEnabled ? '正常' : '已关闭'} · 耗电保护：已就绪</span>
                  <span onclick="navTo('settings')" class="text-[#1b5c45] font-semibold cursor-pointer">设置 &gt;</span>
                </div>
              </div>

              <!-- 核心资产卡片：大字呈现总额度，次级清晰呈现本段/结余双轨明细，真实严谨 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-4 shadow-[0_2px_10px_rgba(20,50,40,0.03)] space-y-2.5">
                <div class="flex justify-between items-center text-xs">
                  <span class="font-bold text-[#163027]">本阶段 (08:00 — 12:00)</span>
                  <span class="text-[10px] text-[#1b5c45] font-semibold bg-[#eaf3ef] px-2 py-0.5 rounded-full">额度已滚存</span>
                </div>

                <!-- 双栏大数字：当前总剩余 -->
                <div class="grid grid-cols-2 gap-4 py-1 border-b border-[#edf3f0]">
                  <div class="flex items-baseline gap-1">
                    <span class="text-3xl font-bold text-[#163027] font-mono-num">${appState.remainingMinutes}</span>
                    <span class="text-xs text-[#5c736a]">分钟</span>
                  </div>
                  <div class="flex items-baseline gap-1 justify-end">
                    <span class="text-3xl font-bold text-[#163027] font-mono-num">${appState.remainingCount}</span>
                    <span class="text-xs text-[#5c736a]">次</span>
                  </div>
                </div>

                <!-- 双轨明细账本：本段额度 + 滚存结余 -->
                <div class="grid grid-cols-2 gap-2 text-[11px] text-[#526a61] bg-[#f7faf8] p-2 rounded-2xl border border-[#e2ede7]">
                  <div>• 本段额度：<span class="font-bold text-[#163027] font-mono-num">15</span>/30 分钟 · <span class="font-bold text-[#163027] font-mono-num">1.5</span>/2 次</div>
                  <div class="text-right">• 滚存结余：<span class="font-bold text-[#163027] font-mono-num">10</span>/20 分钟 · <span class="font-bold text-[#163027] font-mono-num">0.5</span>/1 次</div>
                </div>

                <!-- 暖杏色借用警示条 -->
                <div class="bg-[#fef4e8] border border-[#fae5cc] rounded-2xl p-2.5 text-xs text-[#8c531b] space-y-0.5">
                  <div class="font-medium"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>已借 5 分钟 · 0.5 次</div>
                  <div class="text-[10px] text-[#9c6328]">下一段将双倍扣减 10 分钟 · 1 次</div>
                </div>

                <!-- 下一段信息 -->
                <div class="pt-1 text-xs text-[#4e665d] flex justify-between">
                  <span>下一段扣减后剩余：</span>
                  <span class="font-bold text-[#163027] font-mono-num">50 分钟 · 3 次</span>
                </div>
              </div>

              <!-- 双按钮 -->
              <div class="grid grid-cols-2 gap-2 pt-0.5">
                <button onclick="promptApplyRest()" class="py-2.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-full text-xs font-bold shadow-xs transition-all flex items-center justify-center">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 8h1a4 4 0 0 1 0 8h-1"></path><path d="M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z"></path><line x1="6" y1="1" x2="6" y2="4"></line><line x1="10" y1="1" x2="10" y2="4"></line><line x1="14" y1="1" x2="14" y2="4"></line></svg>申请休息
                </button>
                <button onclick="triggerTempAccess()" class="py-2.5 bg-white hover:bg-[#f7faf8] border border-[#d0dfd8] text-[#1a2d26] rounded-full text-xs font-semibold shadow-2xs transition-all flex items-center justify-center">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>超级临时 (&lt;60s)
                </button>
              </div>

              <!-- 每周特权 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 flex justify-between items-center shadow-2xs text-xs">
                <div>
                  <div class="font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 22 22 7 12 2"></polygon><polyline points="2 7 12 7 22 7"></polyline><polyline points="12 22 12 7"></polyline></svg>每周特权</div>
                  <div class="text-[10px] text-[#5c736a]">剩余：120 min · 1 / 2 次 (越过双锁)</div>
                </div>
                <button onclick="showPrivilegeConfirmModal()" class="px-3 py-1 bg-[#1a3830] text-white font-bold rounded-lg text-xs">启用</button>
              </div>

              <!-- 底部说明 -->
              <div class="flex justify-between items-center px-1 text-[11px] text-[#5c736a] pt-1">
                <span>当前状态：监管中</span>
                <span onclick="showModalWithMessage('决定守护哲学', '我们想要的不是更少的自由，而是更确定的自由。')" class="text-[#1b5c45] font-semibold cursor-pointer">📘 使用说明</span>
              </div>
            </div>
          `;
          break;

        // 2. 专注规则 (Tab 切换)
        case 'rules':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <div class="grid grid-cols-2 gap-1 bg-white p-1 rounded-2xl border border-[#dce8e2] shadow-2xs">
                <button class="py-1.5 text-xs font-bold rounded-lg bg-[#1a3830] text-white">勿扰时段</button>
                <button onclick="showToast('切换至应用时间窗规则')" class="py-1.5 text-xs font-medium text-[#5c736a]">应用时间窗</button>
              </div>

              <div class="bg-[#fef4e8] border border-[#fae5cc] rounded-2xl p-2.5 text-xs text-[#8c531b]">
                <div class="font-bold flex items-center gap-1"><span><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 20V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2z"></path><path d="M6 12h12"></path></svg></span> 延迟生效表</div>
                <div class="text-[10px] text-[#9c6328] mt-0.5">加严立即生效 · 放宽次日 08:00 生效</div>
              </div>

              <div class="space-y-2">
                <div class="p-3 bg-white border-2 border-[#1b5c45] rounded-2xl flex justify-between items-center shadow-2xs">
                  <div>
                    <div class="font-bold text-[#163027] text-xs">晨间专注 (当前进行中)</div>
                    <div class="text-[10px] text-[#5c736a]">08:00 — 12:00 · 30m/3次 · 42款受控</div>
                  </div>
                  <span class="text-[10px] px-2 py-0.5 bg-[#eaf3ef] text-[#1b5c45] rounded-md font-bold">进行中 &gt;</span>
                </div>

                <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl flex justify-between items-center shadow-2xs">
                  <div>
                    <div class="font-bold text-[#163027] text-xs">下午专注</div>
                    <div class="text-[10px] text-[#5c736a]">14:00 — 18:00 · 20m/2次 · 42款受控</div>
                  </div>
                  <span class="text-[10px] text-[#738a82]">待生效 &gt;</span>
                </div>

                <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl flex justify-between items-center shadow-2xs">
                  <div>
                    <div class="font-bold text-[#163027] text-xs">晚间自习</div>
                    <div class="text-[10px] text-[#5c736a]">19:30 — 22:30 · 15m/1次 · 42款受控</div>
                  </div>
                  <span class="text-[10px] text-[#738a82]">待生效 &gt;</span>
                </div>
              </div>

              <!-- 原生按键行: 添加/批量/一键方案 -->
              <div class="grid grid-cols-3 gap-1.5 pt-1">
                <button onclick="navTo('sub_edit_dnd_rule')" class="py-2.5 bg-[#1a3830] text-white rounded-2xl text-xs font-bold shadow-xs">
                  + 添加时段
                </button>
                <button onclick="showBatchCycleModal()" class="py-2.5 bg-white border border-[#d0dfd8] text-[#1a2d26] rounded-2xl text-xs font-semibold">
                  批量周期
                </button>
                <button onclick="showModalWithMessage('一键推荐', '将自动套用：睡眠时段、工作勿扰、晚间放松等预设模版。')" class="py-2.5 bg-white border border-[#d0dfd8] text-[#1a2d26] rounded-2xl text-xs font-semibold">
                  一键方案
                </button>
              </div>
            </div>
          `;
          break;

        // 3. 统计
        case 'stats':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <div class="grid grid-cols-3 gap-1 bg-white p-1 rounded-2xl border border-[#dce8e2] shadow-2xs">
                <button class="py-1 text-xs font-bold rounded-lg bg-[#1a3830] text-white">周统计</button>
                <button onclick="showToast('切换到月统计')" class="py-1 text-xs font-medium text-[#5c736a]">月统计</button>
                <button onclick="showToast('切换到年统计')" class="py-1 text-xs font-medium text-[#5c736a]">年统计</button>
              </div>

              <div class="flex justify-between items-center bg-white p-2 rounded-2xl border border-[#dce8e2] text-xs text-[#5c736a]">
                <span class="font-bold text-[#163027]">● 休息时长</span>
                <span class="text-[#738a82]">○ 自律时长</span>
                <label class="flex items-center gap-1 text-[11px]"><input type="checkbox" checked class="accent-[#1b5c45]"> 加权</label>
                <span onclick="showMultiplierModal()" class="text-[#1b5c45] font-bold cursor-pointer">换算表</span>
              </div>

              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3.5 space-y-2 shadow-2xs">
                <div class="flex justify-between items-center text-xs text-[#5c736a] border-b border-[#edf3f0] pb-2">
                  <button onclick="showToast('上一周')" class="px-2 py-0.5 rounded bg-[#f0f6f3]">◀ 上周</button>
                  <span class="font-bold text-[#163027]">本周 (09/01 — 09/07)</span>
                  <button onclick="showToast('已是最新')" class="px-2 py-0.5 rounded bg-[#f0f6f3]">下周 ▶</button>
                </div>
                <div class="flex items-end justify-between h-20 pt-2 px-3 border-b border-[#edf3f0]">
                  <div class="w-4 bg-[#1b5c45] h-14 rounded-t-xs"></div>
                  <div class="w-4 bg-[#1b5c45] h-10 rounded-t-xs"></div>
                  <div class="w-4 bg-[#1b5c45] h-16 rounded-t-xs"></div>
                  <div class="w-4 bg-[#1b5c45] h-8 rounded-t-xs ring-2 ring-[#1b5c45]"></div>
                  <div class="w-4 bg-[#d5e5dd] h-3 rounded-t-xs"></div>
                  <div class="w-4 bg-[#d5e5dd] h-3 rounded-t-xs"></div>
                  <div class="w-4 bg-[#d5e5dd] h-3 rounded-t-xs"></div>
                </div>
                <div class="text-[10px] text-center text-[#5c736a]">周度休息总计 265 分钟 · 日均休息 37.8 分钟</div>
              </div>
            </div>
          `;
          break;

        // 4. 闹钟
        case 'alarm':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <div class="flex justify-between items-center">
                <div>
                  <h2 class="text-sm font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"></circle><polyline points="12 9 12 13 14 15"></polyline><line x1="5" y1="3" x2="7" y2="5"></line><line x1="19" y1="3" x2="17" y2="5"></line></svg>起床闹钟</h2>
                  <p class="text-[10px] text-[#5c736a]">必须离床扫码 · 防止躺着按掉</p>
                </div>
                <button onclick="showModalWithMessage('应急应用', '真实场景下将弹出仅包含电话、微信等允许放行的核心应用列表，用于紧急联系。')" class="text-xs px-2.5 py-1 bg-white border border-[#d0dfd8] text-[#1a3830] font-semibold rounded-lg shadow-2xs">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>应急应用
                </button>
              </div>

              <!-- 全局配置卡 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs space-y-2 text-xs">
                <div onclick="openSnoozeSettingsModal()" class="flex justify-between items-center py-0.5 border-b border-[#edf3f0] cursor-pointer">
                  <span class="font-medium text-[#163027]">再响设置</span>
                  <span class="text-[#5c736a] text-[11px]">${appState.snoozeConfig} &gt;</span>
                </div>
                <div onclick="openKeepAliveModal()" class="flex justify-between items-center py-0.5 border-b border-[#edf3f0] cursor-pointer">
                  <span class="font-medium text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>闹钟保活</span>
                  <span class="text-[#1b5c45] font-semibold text-[11px]">${appState.keepAlive ? '已开启' : '已关闭'} &gt;</span>
                </div>
                <div onclick="openChallengeConfigModal()" class="flex justify-between items-center py-0.5 cursor-pointer">
                  <span class="font-medium text-[#163027]">🌐 默认挑战数量</span>
                  <span class="text-[#5c736a] text-[11px]">${appState.challengeConfig} &gt;</span>
                </div>
              </div>

              <!-- 闹钟列表 -->
              <div class="space-y-2">
                ${appState.alarms.map(a => `
                  <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl shadow-2xs space-y-1.5">
                    <div class="flex justify-between items-center">
                      <div>
                        <span class="text-2xl font-bold font-mono-num text-[#163027]">${a.time}</span>
                        <div class="text-[10px] text-[#5c736a]">${a.repeat} · ${a.label}</div>
                      </div>
                      <div onclick="toggleAlarm(${a.id})" class="w-11 h-6 ${a.enabled ? 'bg-[#1a3830]' : 'bg-[#c2d1cc]'} rounded-full p-0.5 flex items-center ${a.enabled ? 'justify-end' : 'justify-start'} cursor-pointer transition-all">
                        <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                      </div>
                    </div>
                    <div class="pt-2 border-t border-[#edf3f0] flex justify-between text-[11px] text-[#4e665d]">
                      <span>${a.challenge}</span>
                      <span class="text-[#1b5c45] font-semibold">🔁 4分钟防回马枪</span>
                    </div>
                  </div>
                `).join('')}
              </div>

              <!-- 操作按钮行: 添加闹钟 + 测试闹钟 -->
              <div class="grid grid-cols-2 gap-2">
                <button onclick="openAlarmEditModal()" class="py-2.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-2xl text-xs font-bold shadow-xs transition-all flex items-center justify-center gap-1">
                  <span>+</span> 添加新闹钟
                </button>
                <button onclick="testAlarmChallengeModal()" class="py-2.5 bg-white hover:bg-[#f7faf8] border border-[#d0dfd8] text-[#1a2d26] rounded-2xl text-xs font-semibold shadow-2xs transition-all flex items-center justify-center gap-1">
                  <span><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path><path d="M13.73 21a2 2 0 0 1-3.46 0"></path></svg></span> 测试响铃挑战
                </button>
              </div>

              <!-- 离床错题收藏本 (fragment_alarm.xml 核心真实组件) -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3.5 shadow-2xs space-y-2 text-xs">
                <div class="flex justify-between items-center">
                  <span class="font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>离床挑战题目收藏本</span>
                  <span class="text-[10px] text-[#5c736a]">跳过/答错题目自动归集</span>
                </div>
                
                <div class="grid grid-cols-2 gap-2 pt-1">
                  <button onclick="openBookmarkTab('poems')" class="p-2.5 bg-[#f4f8f6] border border-[#dce8e2] rounded-2xl text-left hover:bg-[#eaf3ef] transition-all">
                    <div class="font-bold text-[#1b5c45] text-xs"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>收藏的诗词</div>
                    <div class="text-[10px] text-[#5c736a] mt-0.5">${appState.bookmarks.poems.length} 首 · 按跳过排序 &gt;</div>
                  </button>
                  <button onclick="openBookmarkTab('words')" class="p-2.5 bg-[#f4f8f6] border border-[#dce8e2] rounded-2xl text-left hover:bg-[#eaf3ef] transition-all">
                    <div class="font-bold text-[#1b5c45] text-xs"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path></svg>收藏的单词</div>
                    <div class="text-[10px] text-[#5c736a] mt-0.5">${appState.bookmarks.words.length} 词 · 音标与释义 &gt;</div>
                  </button>
                </div>

                <button onclick="openBookmarkTab('clozes')" class="w-full p-2.5 bg-[#f4f8f6] border border-[#dce8e2] rounded-2xl text-left hover:bg-[#eaf3ef] transition-all flex justify-between items-center">
                  <div>
                    <div class="font-bold text-[#1b5c45] text-xs"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>收藏的选词填空</div>
                    <div class="text-[10px] text-[#5c736a] mt-0.5">${appState.bookmarks.clozes.length} 题 · 句子完形强化复习</div>
                  </div>
                  <span class="text-xs text-[#1b5c45] font-bold">&gt;</span>
                </button>
              </div>
            </div>
          `;
          break;

        // 5. 设置 (包含点进黑白名单、全局参数与无障碍服务开关)
        case 'settings':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <!-- 第1行: 黑名单 + 全局参数 (点击直接进入真实二级页面) -->
              <div class="grid grid-cols-2 gap-2">
                <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs space-y-2">
                  <div class="font-bold text-xs text-[#163027]">黑白名单</div>
                  <p class="text-[10px] text-[#5c736a]">当前受限 ${appState.selectedApps.length} 款</p>
                  <button onclick="navTo('sub_app_selection')" class="w-full py-1.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-2xl text-xs font-bold shadow-xs transition-all">
                    进入设置 &gt;
                  </button>
                </div>

                <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs space-y-2">
                  <div class="font-bold text-xs text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>全局参数</div>
                  <p class="text-[10px] text-[#5c736a]">刷新 ${appState.baselineHour} · 借用 ${appState.maxBorrowRatio}</p>
                  <button onclick="navTo('sub_global_params')" class="w-full py-1.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-2xl text-xs font-bold shadow-xs transition-all">
                    进入设置 &gt;
                  </button>
                </div>
              </div>

              <!-- 生效策略 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs space-y-1.5">
                <div class="font-bold text-xs text-[#163027]">⏱️ 生效策略</div>
                <div class="space-y-1 text-xs text-[#4e665d]">
                  <label class="flex items-center gap-2 p-1.5 rounded-lg bg-[#f0f6f3] font-semibold text-[#163027]">
                    <input type="radio" name="p" checked class="accent-[#1b5c45]"> 「宽严有别」（加严立即，放宽次日）
                  </label>
                  <label class="flex items-center gap-2 p-1.5 text-[#5c736a]">
                    <input type="radio" name="p" class="accent-[#1b5c45]"> 「全部次日」（所有操作次日生效）
                  </label>
                </div>
              </div>

              <!-- 加强约束功能 大框 (fragment_rules.xml 核心：全量依赖无障碍) -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3.5 shadow-2xs space-y-2">
                <div class="flex justify-between items-center">
                  <span class="font-bold text-xs text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>加强约束守护 (依赖无障碍)</span>
                  <span class="text-[10px] text-[#5c736a]">防冲动核心防线</span>
                </div>

                <div class="space-y-2 pt-1 border-t border-[#edf3f0] text-xs">
                  <!-- 1. 无障碍服务 -->
                  <div class="flex justify-between items-center py-1">
                    <div>
                      <div class="font-semibold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"></circle><path d="M12 12a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"></path><path d="M12 12v6"></path><path d="M9 16l3 2 3-2"></path></svg>无障碍服务</div>
                      <div class="text-[10px] ${appState.a11yEnabled ? 'text-[#1b5c45]' : 'text-rose-600'}">
                        ${appState.a11yEnabled ? '正常运行 · 前台与窗口识别正常' : '已关闭服务 (保护失效)'}
                      </div>
                    </div>
                    <div onclick="toggleA11yService()" class="w-11 h-6 ${appState.a11yEnabled ? 'bg-[#1a3830]' : 'bg-[#c2d1cc]'} rounded-full p-0.5 flex items-center ${appState.a11yEnabled ? 'justify-end' : 'justify-start'} cursor-pointer transition-all">
                      <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                    </div>
                  </div>

                  <!-- 2. 防卸载保护 -->
                  <div class="flex justify-between items-center py-1 border-t border-[#edf3f0]">
                    <div>
                      <div class="font-semibold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>防卸载保护</div>
                      <div class="text-[10px] ${appState.pendingAntiUninstallDisable ? 'text-amber-600' : 'text-[#5c736a]'}">
                        ${appState.pendingAntiUninstallDisable ? `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 20V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2z"></path><path d="M6 12h12"></path></svg>申请次日 08:00 解除，当前仍拦截` : (appState.antiUninstallEnabled ? '拦截系统应用信息页与安装器' : '已关闭保护')}
                      </div>
                    </div>
                    <button onclick="toggleAntiUninstall()" class="px-2.5 py-1 ${appState.pendingAntiUninstallDisable ? 'bg-amber-100 text-amber-800' : (appState.antiUninstallEnabled ? 'bg-[#fef4e8] text-[#8c531b]' : 'bg-[#f0f6f3] text-[#5c736a]')} border border-[#fae5cc] rounded-lg text-xs font-semibold">
                      ${appState.pendingAntiUninstallDisable ? '已申请次日解除' : (appState.antiUninstallEnabled ? '申请解除' : '点击开启')}
                    </button>
                  </div>

                  <!-- 3. 防关权限保护 -->
                  <div class="flex justify-between items-center py-1 border-t border-[#edf3f0]">
                    <div>
                      <div class="font-semibold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>防关权限保护</div>
                      <div class="text-[10px] ${appState.pendingAntiRevokeDisable ? 'text-amber-600' : 'text-[#5c736a]'}">
                        ${appState.pendingAntiRevokeDisable ? `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 20V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2z"></path><path d="M6 12h12"></path></svg>待次日 08:00 解除，当前仍拦截` : (appState.antiRevokePermEnabled ? '拦截系统权限与辅助功能关闭' : '已关闭保护')}
                      </div>
                    </div>
                    <button onclick="toggleAntiRevoke()" class="px-2.5 py-1 ${appState.pendingAntiRevokeDisable ? 'bg-amber-100 text-amber-800' : (appState.antiRevokePermEnabled ? 'bg-[#fef4e8] text-[#8c531b]' : 'bg-[#f0f6f3] text-[#5c736a]')} border border-[#fae5cc] rounded-lg text-xs font-semibold">
                      ${appState.pendingAntiRevokeDisable ? '已申请次日解除' : (appState.antiRevokePermEnabled ? '申请解除' : '点击开启')}
                    </button>
                  </div>

                  <!-- 4. 隐私空间拦截 -->
                  <div class="flex justify-between items-center py-1 border-t border-[#edf3f0]">
                    <div>
                      <div class="font-semibold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>隐私空间拦截</div>
                      <div class="text-[10px] ${appState.pendingPrivacyGuardDisable ? 'text-amber-600' : 'text-[#5c736a]'}">
                        ${appState.pendingPrivacyGuardDisable ? `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 20V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2z"></path><path d="M6 12h12"></path></svg>待次日 08:00 解除，当前仍拦截` : (appState.privacySpaceGuardEnabled ? '拦截系统分身、双开与隐私空间' : '已关闭拦截')}
                      </div>
                    </div>
                    <button onclick="togglePrivacyGuard()" class="px-2.5 py-1 ${appState.pendingPrivacyGuardDisable ? 'bg-amber-100 text-amber-800' : (appState.privacySpaceGuardEnabled ? 'bg-[#fef4e8] text-[#8c531b]' : 'bg-[#f0f6f3] text-[#5c736a]')} border border-[#fae5cc] rounded-lg text-xs font-semibold">
                      ${appState.pendingPrivacyGuardDisable ? '已申请次日解除' : (appState.privacySpaceGuardEnabled ? '申请解除' : '点击开启')}
                    </button>
                  </div>

                  <!-- 5. 闹钟防关机保护 -->
                  <div class="flex justify-between items-center py-1 border-t border-[#edf3f0]">
                    <div>
                      <div class="font-semibold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"></circle><polyline points="12 9 12 13 14 15"></polyline><line x1="5" y1="3" x2="7" y2="5"></line><line x1="19" y1="3" x2="17" y2="5"></line></svg>闹钟防关机保护</div>
                      <div class="text-[10px] text-[#5c736a]">
                        ${appState.alarmPowerOffProtection ? '响铃期间拦截系统长按电源关机' : '已关闭防关机'}
                      </div>
                    </div>
                    <div onclick="toggleAlarmPowerOffProtection()" class="w-11 h-6 ${appState.alarmPowerOffProtection ? 'bg-[#1a3830]' : 'bg-[#c2d1cc]'} rounded-full p-0.5 flex items-center ${appState.alarmPowerOffProtection ? 'justify-end' : 'justify-start'} cursor-pointer transition-all">
                      <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                    </div>
                  </div>

                  <!-- 6. 在多任务界面中隐藏决定守护 -->
                  <div class="flex justify-between items-center py-1 border-t border-[#edf3f0]">
                    <div>
                      <div class="font-semibold text-[#163027]">在多任务界面中隐藏决定守护</div>
                      <div class="text-[10px] text-[#5c736a]">Recents 最近任务列表防滑除</div>
                    </div>
                    <div onclick="toggleHideInRecentTasks()" class="w-11 h-6 ${appState.hideInRecentTasks ? 'bg-[#1a3830]' : 'bg-[#c2d1cc]'} rounded-full p-0.5 flex items-center ${appState.hideInRecentTasks ? 'justify-end' : 'justify-start'} cursor-pointer transition-all">
                      <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                    </div>
                  </div>
                </div>
              </div>

              
                  <div class="text-[10px] text-[#5c736a]">每周一次的豁免特权机会 (120min · 1/2次)</div>
                </div>
                <button onclick="showModalWithMessage('每周特权', '每周仅有一次的2小时特权机会，使用后需等待下周刷新。')" class="px-2.5 py-1 bg-[#1a3830] text-white rounded-lg text-xs font-bold">配置</button>
              </div>

              <!-- REQ-5: ℹ️ 关于我们 (7项法务/合规入口) -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs flex justify-between items-center text-xs">
                <div>
                  <div class="font-bold text-[#163027]">ℹ️ 关于我们</div>
                  <div class="text-[10px] text-[#5c736a]">用户协议、隐私政策、第三方 SDK 清单等 7 项</div>
                </div>
                <button onclick="showModalWithMessage('关于我们', '包含用户协议、隐私政策等7项合规文件列表。')" class="px-2.5 py-1 bg-white border border-[#d0dfd8] text-[#1a2d26] rounded-lg text-xs font-semibold">查看 &gt;</button>
              </div>

              <!-- 第4行: 日志诊断 + 设置备份 -->
              <div class="grid grid-cols-2 gap-2 text-xs">
                <button onclick="showToast('✅ 日志打包完成，调起系统分享...')" class="p-2.5 bg-white border border-[#dce8e2] rounded-2xl text-left shadow-2xs">
                  <div class="font-bold text-[#163027]">🧾 日志诊断</div>
                  <div class="text-[10px] text-[#738a82]">一键导出发送日志</div>
                </button>
                <button onclick="showToast('💾 备份已保存至 Download/DeciKeeper 目录')" class="p-2.5 bg-white border border-[#dce8e2] rounded-2xl text-left shadow-2xs">
                  <div class="font-bold text-[#163027]">💾 设置备份</div>
                  <div class="text-[10px] text-[#738a82]">导出 / 导入配置</div>
                </button>
              </div>
            </div>
          `;
          break;

        // 真实二级页面 1: 黑白名单选择页 (AppSelectionActivity)
        
        case 'sub_app_window':
          content.innerHTML = `
            <div class="p-4 space-y-3 pb-20 h-full overflow-y-auto">
              <!-- 顶部返回栏 -->
              <div class="flex items-center justify-between pb-2">
                <button onclick="navTo('settings')" class="text-xs font-bold text-[#1b5c45] flex items-center gap-1">
                  ← 返回设置
                </button>
                <button onclick="navTo('sub_app_window_preview')" class="text-xs font-bold text-[#1b5c45]">
                  排程预览
                </button>
              </div>

              <div class="text-[24px] font-bold text-[#333333]">应用时间窗规则</div>
              <div class="text-sm text-[#666666] mb-2">应用仅在设定时段内可用（需同时处于休息或空档期）</div>

              <!-- Pending Changes Card -->
              <div class="bg-[#FFF8E1] rounded-2xl p-3 shadow-2xs mb-3 border border-[#FFE082]">
                <div class="text-sm font-bold text-[#F57C00] mb-1">⏳ 延迟生效表</div>
                <div class="text-[13px] text-[#795548]">待生效变更将于次日 08:00 执行</div>
              </div>

              <!-- Rules List -->
              <div class="space-y-3">
                ${appState.appWindowRules.length === 0 ? '<div class="text-center text-[#999999] py-8 text-sm">暂无规则，点击下方按钮添加</div>' : ''}
                ${appState.appWindowRules.map(rule => `
                  <div class="bg-white rounded-2xl p-3 shadow-2xs border border-[#E0E0E0]">
                    <div class="flex justify-between items-center mb-2">
                      <div class="flex items-center gap-2">
                        <span class="text-base font-bold text-[#006064]">${rule.name}</span>
                        <span class="text-sm font-mono font-bold text-[#004D40]">${rule.start} - ${rule.end}</span>
                      </div>
                      <button onclick="deleteAppWindowRule(${rule.id})" class="p-1.5 bg-[#FFE0E0] rounded-lg text-[#D32F2F]">
                        🗑️
                      </button>
                    </div>
                    <div class="flex justify-between items-center mb-2">
                      <span class="text-xs px-2 py-1 bg-[#E0F2F1] text-[#F57C00] rounded-lg">${rule.days.length === 7 ? '每天' : rule.days.length === 5 ? '工作日' : rule.days.length === 2 ? '周末' : '自定义'}</span>
                      <div onclick="toggleAppWindowRule(${rule.id})" class="w-11 h-6 ${rule.enabled ? 'bg-[#5E35B1]' : 'bg-[#D1C4E9]'} rounded-full p-0.5 flex items-center ${rule.enabled ? 'justify-end' : 'justify-start'} cursor-pointer transition-all">
                        <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                      </div>
                    </div>
                    <div class="flex items-center gap-1 overflow-x-auto pt-2 border-t border-[#EEEEEE]" onclick="editAppWindowRule(${rule.id})">
                      ${rule.apps.map(app => {
                         let icon = '📱';
                         if(app.includes('aweme') || app.includes('gifmaker')) icon = '🎵';
                         if(app.includes('bilibili')) icon = '📺';
                         if(app.includes('mm')) icon = '💬';
                         return `<span class="text-xl bg-[#F5F5F5] p-1 rounded">${icon}</span>`;
                      }).join('')}
                    </div>
                  </div>
                `).join('')}
              </div>
              
              <!-- Bottom Controls -->
              <div class="flex gap-2 mt-4">
                <button onclick="editAppWindowRule(null)" class="flex-1 py-3 bg-[#C8E6C9] text-[#1B5E20] font-bold rounded-full shadow-sm flex justify-center items-center gap-1">
                  <span>+</span> 添加时间窗
                </button>
                <button onclick="showBatchCycleModal()" class="flex-1 py-3 bg-[#E1BEE7] text-[#4A148C] font-bold rounded-full shadow-sm">
                  批量设置周期
                </button>
              </div>
            </div>
          `;
          break;

        
        case 'sub_edit_dnd_rule':
          content.innerHTML = `
            <div class="p-5 space-y-4 bg-[#F5F5F5] h-full overflow-y-auto">
              <div class="flex items-center justify-between pb-2 border-b border-[#EEEEEE]">
                <button onclick="navTo('rules')" class="text-xs font-bold text-[#1a3830] flex items-center gap-1">← 取消</button>
                <span class="text-sm font-bold">编辑勿扰时段</span>
                <button onclick="showToast('规则已保存'); navTo('rules')" class="text-xs font-bold text-[#1a3830]">保存</button>
              </div>
              
              <div class="bg-white rounded-xl p-4 space-y-4">
                <div>
                  <div class="text-sm font-bold text-[#333333] mb-2">时段名称</div>
                  <input type="text" value="午休模式" class="w-full bg-[#f4f8f6] rounded-lg border-none py-2 px-3 text-sm outline-none focus:ring-1 focus:ring-[#1a3830]">
                </div>

                <div>
                  <div class="text-sm font-bold text-[#333333] mb-2">控制时间</div>
                  <div class="flex items-center justify-between">
                    <button class="flex-1 py-3 bg-[#f4f8f6] rounded-lg text-lg font-mono font-bold text-[#1a3830]">13:00</button>
                    <span class="mx-3 text-[#5c736a]">至</span>
                    <button class="flex-1 py-3 bg-[#f4f8f6] rounded-lg text-lg font-mono font-bold text-[#1a3830]">14:00</button>
                  </div>
                </div>

                <div>
                  <div class="text-sm font-bold text-[#333333] mb-2">重复周期</div>
                  <div class="flex gap-2 mb-3">
                    <button class="flex-1 py-1.5 text-xs font-bold text-[#5c736a] bg-[#f4f8f6] rounded-full">每天</button>
                    <button class="flex-1 py-1.5 text-xs font-bold text-white bg-[#1a3830] rounded-full shadow-sm">工作日</button>
                    <button class="flex-1 py-1.5 text-xs font-bold text-[#5c736a] bg-[#f4f8f6] rounded-full">周末</button>
                  </div>
                  <div class="flex justify-between">
                    ${['日','一','二','三','四','五','六'].map((day, i) => `
                      <div class="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold ${[1,2,3,4,5].includes(i) ? 'bg-[#1a3830] text-white' : 'bg-[#f4f8f6] text-[#666666]'}">${day}</div>
                    `).join('')}
                  </div>
                </div>
              </div>
              
              <div class="bg-white rounded-xl p-4">
                <div class="flex justify-between items-center">
                  <div>
                    <div class="text-sm font-bold text-[#333333]">强力锁死模式</div>
                    <div class="text-[10px] text-[#E65100] mt-1">此模式下无法申请休息，无法提前退出</div>
                  </div>
                  <div class="w-11 h-6 bg-[#D1C4E9] rounded-full p-0.5 flex items-center justify-start cursor-pointer">
                    <div class="w-5 h-5 bg-white rounded-full shadow-sm"></div>
                  </div>
                </div>
              </div>
              
              <button class="w-full py-3 bg-[#FFE0E0] text-[#D32F2F] font-bold rounded-xl shadow-sm mt-4" onclick="showToast('已删除'); navTo('rules')">
                🗑️ 删除此规则
              </button>
            </div>
          `;
          break;

        case 'sub_edit_app_window':
          const r = appState.editingRuleId ? appState.appWindowRules.find(x => x.id === appState.editingRuleId) : { name: '', start: '00:00', end: '23:59', days: [], apps: [] };
          content.innerHTML = `
            <div class="p-5 space-y-4 bg-white h-full overflow-y-auto">
              <div class="flex items-center justify-between pb-2 border-b border-[#EEEEEE]">
                <button onclick="navTo('sub_app_window')" class="text-xs font-bold text-[#1b5c45] flex items-center gap-1">← 取消</button>
                <span class="text-sm font-bold">${appState.editingRuleId ? '编辑规则' : '新增规则'}</span>
                <button onclick="saveAppWindowRule()" class="text-xs font-bold text-[#1b5c45]">保存</button>
              </div>
              
              <div class="space-y-1">
                <div class="text-sm font-bold text-[#333333]">规则名称</div>
                <input type="text" id="ruleName" value="${r.name}" class="w-full border-b border-[#CCCCCC] py-2 text-base outline-none focus:border-[#1b5c45]" placeholder="如: 晚间娱乐">
              </div>

              <div class="space-y-1 mt-4">
                <div class="text-sm font-bold text-[#333333]">生效时段</div>
                <div class="flex items-center justify-between mt-2">
                  <button class="flex-1 py-2 border border-[#CCCCCC] rounded-lg text-lg font-mono">${r.start}</button>
                  <span class="mx-4 text-[#666666]">至</span>
                  <button class="flex-1 py-2 border border-[#CCCCCC] rounded-lg text-lg font-mono">${r.end}</button>
                </div>
              </div>

              <div class="space-y-1 mt-4">
                <div class="text-sm font-bold text-[#333333]">重复周期</div>
                <div class="flex gap-2 mt-2 mb-2">
                  <button class="px-4 py-1.5 text-sm font-bold text-[#5E35B1] bg-[#EDE7F6] rounded-full">每天</button>
                  <button class="px-4 py-1.5 text-sm font-bold text-[#666666] bg-[#F5F5F5] rounded-full">工作日</button>
                  <button class="px-4 py-1.5 text-sm font-bold text-[#666666] bg-[#F5F5F5] rounded-full">周末</button>
                </div>
                <div class="flex justify-between">
                  ${['日','一','二','三','四','五','六'].map((day, i) => `
                    <div class="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold ${r.days.includes(i) ? 'bg-[#5E35B1] text-white' : 'bg-[#F5F5F5] text-[#666666] border border-[#EEEEEE]'}">${day}</div>
                  `).join('')}
                </div>
              </div>

              <div class="space-y-1 mt-4">
                <div class="text-sm font-bold text-[#333333]">受控应用</div>
                <button onclick="navTo('sub_app_selection')" class="mt-2 px-4 py-2 bg-[#F5F5F5] text-[#333333] rounded-lg font-bold border border-[#E0E0E0] text-sm">
                  选择应用
                </button>
                <div class="flex flex-wrap gap-2 mt-2">
                  ${r.apps.map(app => `<div class="px-3 py-1 bg-[#E8F5E9] border border-[#C8E6C9] rounded-full text-xs text-[#2E7D32] flex items-center gap-1">${app.split('.').pop()} <span class="text-[#1B5E20]">✕</span></div>`).join('')}
                </div>
              </div>
            </div>
          `;
          break;

        case 'sub_app_window_preview':
          content.innerHTML = `
            <div class="h-full flex flex-col bg-[#F5F5F5]">
              <div class="bg-[#1b5c45] text-white p-4 flex items-center gap-4">
                <button onclick="navTo('sub_app_window')" class="text-white font-bold">←</button>
                <span class="text-lg font-bold">排程预览</span>
              </div>
              
              <div class="bg-white elevation-4 pb-2">
                <div class="flex border-b border-[#EEEEEE]">
                  <div class="flex-1 py-3 text-center text-[#1b5c45] font-bold border-b-2 border-[#1b5c45]">今天</div>
                  <div class="flex-1 py-3 text-center text-[#666666]">明天</div>
                  <div class="flex-1 py-3 text-center text-[#666666]">后天</div>
                </div>
                <div class="flex justify-center mt-3">
                  <div class="flex border border-[#DDDDDD] rounded-lg overflow-hidden">
                    <button onclick="appState.previewTab='app'; renderCurrentScreen();" class="px-4 py-1.5 text-[13px] ${appState.previewTab === 'app' ? 'bg-[#EEEEEE] font-bold' : 'bg-white'}">按应用查看</button>
                    <button onclick="appState.previewTab='time'; renderCurrentScreen();" class="px-4 py-1.5 text-[13px] ${appState.previewTab === 'time' ? 'bg-[#EEEEEE] font-bold' : 'bg-white'} border-l border-[#DDDDDD]">按时段查看</button>
                  </div>
                </div>
              </div>

              <div class="flex-1 overflow-y-auto p-4 space-y-3">
                ${appState.appWindowRules.filter(r => r.enabled).length === 0 ? '<div class="text-center text-[#999999] py-8">该日无生效规则</div>' : ''}
                ${appState.appWindowRules.filter(r => r.enabled).map(r => `
                  <div class="bg-white rounded-xl p-3 shadow-sm border border-[#E0E0E0]">
                    <div class="text-[#004D40] font-mono font-bold">${r.start} - ${r.end}</div>
                    <div class="text-[#666666] text-xs mt-1">关联规则: ${r.name}</div>
                    <div class="flex flex-wrap gap-1 mt-2">
                      ${r.apps.map(app => `<span class="px-2 py-0.5 bg-[#F5F5F5] rounded text-[10px] text-[#333333] border border-[#EEEEEE]">${app.split('.').pop()}</span>`).join('')}
                    </div>
                  </div>
                `).join('')}
              </div>
            </div>
          `;
          break;

        case 'sub_app_selection':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <!-- 顶部返回栏 -->
              <div class="flex items-center justify-between pb-2 border-b border-[#edf3f0]">
                <button onclick="navTo('settings')" class="text-xs font-bold text-[#1b5c45] flex items-center gap-1">
                  ← 返回设置
                </button>
                <span class="text-xs font-bold text-[#163027]">🛑 屏蔽应用名单</span>
                <span class="w-8"></span>
              </div>

              <!-- 黑白名单模式切换 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3 shadow-2xs flex items-center justify-between">
                <div>
                  <div class="text-xs font-bold text-[#163027]">
                    ${appState.listMode === 'blacklist' ? '⚫ 黑名单模式' : '⚪ 白名单模式'}
                  </div>
                  <div class="text-[10px] text-[#5c736a]">
                    ${appState.listMode === 'blacklist' ? '勾选的应用在专注时段被拦截' : '仅勾选的应用允许通行'}
                  </div>
                </div>
                <button onclick="toggleListMode()" class="px-2.5 py-1 text-xs bg-[#f0f6f3] border border-[#d0dfd8] text-[#1a3830] font-semibold rounded-lg">
                  切换模式
                </button>
              </div>

              <!-- 搜索框 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl px-3 py-2 flex items-center gap-2 text-xs shadow-2xs">
                <span>🔍</span>
                <input type="text" placeholder="搜索已安装应用..." class="bg-transparent flex-1 outline-none text-xs text-[#1c2e28]">
              </div>

              <!-- 应用列表 -->
              <div class="space-y-1.5">
                ${[
                  { pkg: 'com.ss.android.ugc.aweme', name: '抖音', icon: '🎵', tag: '短视频' },
                  { pkg: 'com.smile.gifmaker', name: '快手', icon: `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>`, tag: '短视频' },
                  { pkg: 'com.bilibili.app.in', name: '哔哩哔哩', icon: '📺', tag: '视频娱乐' },
                  { pkg: 'com.miHoYo.Yuanshen', name: '原神', icon: `⚔️`, tag: '游戏' },
                  { pkg: 'com.tencent.mm', name: '微信', icon: '💬', tag: '社交聊天' },
                  { pkg: 'com.zhihu.android', name: '知乎', icon: '💡', tag: '资讯问答' }
                ].map(app => {
                  const isChecked = appState.selectedApps.includes(app.pkg);
                  return `
                    <div onclick="toggleAppSelection('${app.pkg}')" class="p-2.5 bg-white border ${isChecked ? 'border-[#1b5c45]' : 'border-[#dce8e2]'} rounded-2xl flex items-center justify-between shadow-2xs cursor-pointer">
                      <div class="flex items-center gap-2.5">
                        <span class="text-xl">${app.icon}</span>
                        <div>
                          <div class="text-xs font-bold text-[#163027]">${app.name}</div>
                          <div class="text-[9px] text-[#738a82] font-mono">${app.pkg}</div>
                        </div>
                      </div>
                      <div class="flex items-center gap-2">
                        <span class="text-[9px] px-1.5 py-0.5 rounded bg-[#f0f6f3] text-[#5c736a]">${app.tag}</span>
                        <div class="w-5 h-5 rounded-md border ${isChecked ? 'bg-[#1a3830] border-[#1a3830]' : 'border-[#c0d4cc]'} flex items-center justify-center text-white text-xs font-bold">
                          ${isChecked ? '✓' : ''}
                        </div>
                      </div>
                    </div>
                  `;
                }).join('')}
              </div>

              <!-- 底部保存条 -->
              <button onclick="saveAppSelection()" class="w-full py-2.5 bg-[#1a3830] text-white rounded-2xl text-xs font-bold shadow-sm">
                保存更改 (已选 ${appState.selectedApps.length} 款)
              </button>
            </div>
          `;
          break;

        // 真实二级页面 2: 全局参数设置 (GlobalParamsActivity)
        case 'sub_global_params':
          content.innerHTML = `
            <div class="p-4 space-y-3">
              <div class="flex items-center justify-between pb-2 border-b border-[#edf3f0]">
                <button onclick="navTo('settings')" class="text-xs font-bold text-[#1b5c45] flex items-center gap-1">
                  ← 返回设置
                </button>
                <span class="text-xs font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>全局参数配置</span>
                <span class="w-8"></span>
              </div>

              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3.5 shadow-2xs space-y-3 text-xs">
                <div>
                  <div class="font-bold text-[#163027] text-xs">每日基准刷新时刻 (Baseline)</div>
                  <p class="text-[10px] text-[#5c736a] mt-0.5">所有放宽变更统一于此时刻正式生效落地</p>
                  <div class="mt-2 flex items-center gap-2">
                    <input type="text" value="${appState.baselineHour}" id="input-baseline" class="border border-[#d0dfd8] rounded-lg px-2.5 py-1 text-xs font-mono font-bold text-[#1b5c45] w-24">
                    <span class="text-[11px] text-[#738a82]">默认每日清晨 08:00</span>
                  </div>
                </div>

                <div class="pt-2 border-t border-[#edf3f0]">
                  <div class="font-bold text-[#163027] text-xs">下一段最大借用比例上限</div>
                  <p class="text-[10px] text-[#5c736a] mt-0.5">允许向下一时段借用额度的最高百分比</p>
                  <div class="mt-2 flex items-center gap-2">
                    <button class="px-2.5 py-1 rounded bg-[#f0f6f3] border border-[#d0dfd8]">30%</button>
                    <button class="px-2.5 py-1 rounded bg-[#1a3830] text-white font-bold">50% (推荐)</button>
                    <button class="px-2.5 py-1 rounded bg-[#f0f6f3] border border-[#d0dfd8]">80%</button>
                  </div>
                </div>

                <div class="pt-2 border-t border-[#edf3f0]">
                  <div class="font-bold text-[#163027] text-xs">借用惩罚倍率 (罚则契约)</div>
                  <p class="text-[10px] text-[#5c736a] mt-0.5">借 1 分钟扣 2 分钟，确保借用具有切实代价</p>
                  <div class="mt-1 text-xs font-bold text-[#8c531b] font-mono">固定 2.0x (双倍扣减)</div>
                </div>
              </div>

              
              <!-- 每周特权设置 (原在独立卡片，现归属全局参数) -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-3.5 shadow-2xs space-y-3 text-xs mt-3">
                <div class="flex justify-between items-center border-b border-[#edf3f0] pb-2">
                  <div>
                    <div class="font-bold text-[#163027] text-xs">📅 次周生效 (每周特权)</div>
                    <p class="text-[10px] text-[#5c736a] mt-0.5">利好立即生效，不利修改(增加次数)下周生效</p>
                  </div>
                  <div class="w-8 h-8 rounded-full bg-[#f4f8f6] flex items-center justify-center text-[#1b5c45]">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><polygon points="12 2 2 7 12 22 22 7 12 2"></polygon><polyline points="2 7 12 7 22 7"></polyline><polyline points="12 22 12 7"></polyline></svg>
                  </div>
                </div>

                <div>
                  <div class="font-bold text-[#163027] text-xs">每周特权总次数上限</div>
                  <p class="text-[10px] text-[#5c736a] mt-0.5">本周可使用的越过双锁的放行特权总数</p>
                  <div class="mt-2 flex items-center gap-2">
                    <button class="px-2.5 py-1 rounded bg-[#f0f6f3] border border-[#d0dfd8]">1 次</button>
                    <button class="px-2.5 py-1 rounded bg-[#1a3830] text-white font-bold">2 次</button>
                    <button class="px-2.5 py-1 rounded bg-[#f0f6f3] border border-[#d0dfd8]">3 次</button>
                  </div>
                </div>

                <div class="pt-2 border-t border-[#edf3f0]">
                  <div class="font-bold text-[#163027] text-xs">单次特权放行时长</div>
                  <p class="text-[10px] text-[#5c736a] mt-0.5">每次使用特权可获得的不受限自由时长</p>
                  <div class="mt-2 flex items-center gap-2">
                    <input type="text" value="120" class="border border-[#d0dfd8] rounded-lg px-2.5 py-1 text-xs font-mono font-bold text-[#1b5c45] w-16 text-center">
                    <span class="text-[11px] text-[#738a82]">分钟</span>
                  </div>
                </div>
              </div>

              <button onclick="showToast('全局参数已保存 (加严立即生效，放宽将于次日08:00落地)'); navTo('settings');" class="w-full py-2.5 bg-[#1a3830] text-white rounded-2xl text-xs font-bold shadow-sm">
                保存参数配置
              </button>
            </div>
          `;
          break;

        // 真实二级页面 3: 离床错题收藏夹 (BookmarkActivity.kt / activity_bookmark.xml)
        case 'sub_bookmark':
          let bItems = [...(appState.bookmarks[appState.bookmarkTab] || [])];
          if (appState.bookmarkSearch) {
            const kw = appState.bookmarkSearch.toLowerCase();
            bItems = bItems.filter(it => 
              (it.title && it.title.toLowerCase().includes(kw)) ||
              (it.content && it.content.toLowerCase().includes(kw)) ||
              (it.author && it.author.toLowerCase().includes(kw)) ||
              (it.word && it.word.toLowerCase().includes(kw)) ||
              (it.translation && it.translation.toLowerCase().includes(kw)) ||
              (it.sentence && it.sentence.toLowerCase().includes(kw))
            );
          }
          if (appState.bookmarkSortBy === 'skip') {
            bItems.sort((a, b) => b.skipCount - a.skipCount);
          } else {
            bItems.sort((a, b) => new Date(b.date) - new Date(a.date));
          }

          content.innerHTML = `
            <div class="p-4 space-y-3">
              <!-- 顶部返回栏 -->
              <div class="flex items-center justify-between pb-2 border-b border-[#edf3f0]">
                <button onclick="navTo('alarm')" class="text-xs font-bold text-[#1b5c45] flex items-center gap-1">
                  ← 返回闹钟
                </button>
                <span class="text-xs font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>离床复习收藏夹</span>
                <span class="w-8"></span>
              </div>

              <!-- Tab 切换栏: 诗词 / 单词 / 选词填空 -->
              <div class="grid grid-cols-3 gap-1 bg-white p-1 rounded-2xl border border-[#dce8e2] shadow-2xs text-xs">
                <button onclick="switchBookmarkTab('poems')" class="py-1.5 rounded-lg font-bold ${appState.bookmarkTab === 'poems' ? 'bg-[#1a3830] text-white' : 'text-[#5c736a]'}">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>诗词 (${appState.bookmarks.poems.length})
                </button>
                <button onclick="switchBookmarkTab('words')" class="py-1.5 rounded-lg font-bold ${appState.bookmarkTab === 'words' ? 'bg-[#1a3830] text-white' : 'text-[#5c736a]'}">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path></svg>单词 (${appState.bookmarks.words.length})
                </button>
                <button onclick="switchBookmarkTab('clozes')" class="py-1.5 rounded-lg font-bold ${appState.bookmarkTab === 'clozes' ? 'bg-[#1a3830] text-white' : 'text-[#5c736a]'}">
                  <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>填空 (${appState.bookmarks.clozes.length})
                </button>
              </div>

              <!-- 搜索框 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl px-3 py-2 flex items-center gap-2 text-xs shadow-2xs">
                <span>🔍</span>
                <input type="text" value="${appState.bookmarkSearch}" oninput="searchBookmark(this.value)" placeholder="输入关键词搜索…" class="bg-transparent flex-1 outline-none text-xs text-[#1c2e28]">
                ${appState.bookmarkSearch ? `<button onclick="searchBookmark('')" class="text-xs text-[#738a82]">✕</button>` : ''}
              </div>

              <!-- 排序与时间范围控制栏 -->
              <div class="bg-white border border-[#dce8e2] rounded-2xl p-2.5 shadow-2xs space-y-2 text-xs">
                <div class="flex items-center justify-between">
                  <span class="font-bold text-[#163027] text-[11px]">排序方式</span>
                  <div class="flex items-center gap-1.5">
                    <button onclick="setBookmarkSort('skip')" class="px-2 py-0.5 rounded-lg text-[10px] font-semibold ${appState.bookmarkSortBy === 'skip' ? 'bg-[#1a3830] text-white' : 'bg-[#f0f6f3] text-[#5c736a]'}">
                      跳过次数 (错题优先)
                    </button>
                    <button onclick="setBookmarkSort('time')" class="px-2 py-0.5 rounded-lg text-[10px] font-semibold ${appState.bookmarkSortBy === 'time' ? 'bg-[#1a3830] text-white' : 'bg-[#f0f6f3] text-[#5c736a]'}">
                      收藏时间
                    </button>
                  </div>
                </div>

                <div class="flex items-center justify-between pt-1.5 border-t border-[#edf3f0]">
                  <span class="font-bold text-[#163027] text-[11px]">时间范围</span>
                  <div class="flex items-center gap-1">
                    ${[
                      { id: 'all', label: '全部' },
                      { id: '7d', label: '近7天' },
                      { id: '30d', label: '近30天' },
                      { id: '90d', label: '近90天' }
                    ].map(r => `
                      <button onclick="setBookmarkRange('${r.id}')" class="px-2 py-0.5 rounded-md text-[10px] ${appState.bookmarkRange === r.id ? 'bg-[#1b5c45] text-white font-bold' : 'bg-[#f0f6f3] text-[#738a82]'}">
                        ${r.label}
                      </button>
                    `).join('')}
                  </div>
                </div>
              </div>

              <!-- 列表项容器 -->
              <div class="space-y-2 pb-2">
                ${bItems.length === 0 ? `
                  <div class="p-8 text-center text-xs text-[#738a82] bg-white rounded-2xl border border-[#dce8e2]">
                    暂无匹配的收藏题目
                  </div>
                ` : bItems.map(item => {
                  if (appState.bookmarkTab === 'poems') {
                    return `
                      <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl shadow-2xs space-y-1.5">
                        <div class="flex justify-between items-start">
                          <div>
                            <div class="text-xs font-bold text-[#163027]">${item.title}</div>
                            <div class="text-[10px] text-[#5c736a]">${item.author} · ${item.date}</div>
                          </div>
                          <span class="text-[10px] px-2 py-0.5 rounded-full bg-[#fef4e8] text-[#8c531b] font-bold border border-[#fae5cc]">
                            跳过 ${item.skipCount} 次
                          </span>
                        </div>
                        <p class="text-xs text-[#2c4038] leading-relaxed pt-0.5 bg-[#f7faf8] p-2 rounded-2xl border border-[#edf3f0]">
                          ${item.content}
                        </p>
                        <div class="flex justify-end pt-1">
                          <button onclick="removeBookmark('poems', ${item.id})" class="text-[10px] text-[#738a82] hover:text-rose-600">
                            🗑️ 移出收藏
                          </button>
                        </div>
                      </div>
                    `;
                  } else if (appState.bookmarkTab === 'words') {
                    return `
                      <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl shadow-2xs space-y-1.5">
                        <div class="flex justify-between items-start">
                          <div>
                            <div class="text-sm font-bold text-[#163027] font-mono">${item.word}</div>
                            <div class="text-[10px] text-[#738a82] font-mono">${item.phonetic} · ${item.date}</div>
                          </div>
                          <span class="text-[10px] px-2 py-0.5 rounded-full bg-[#fef4e8] text-[#8c531b] font-bold border border-[#fae5cc]">
                            跳过 ${item.skipCount} 次
                          </span>
                        </div>
                        <div class="text-xs text-[#2c4038] font-medium bg-[#f7faf8] p-2 rounded-2xl border border-[#edf3f0]">
                          ${item.translation}
                        </div>
                        <div class="flex justify-between items-center pt-1 text-[10px]">
                          <span class="text-[#1b5c45] cursor-pointer" onclick="showToast('发音朗读：${item.word}')">🔊 朗读发音</span>
                          <button onclick="removeBookmark('words', ${item.id})" class="text-[#738a82] hover:text-rose-600">
                            🗑️ 移出收藏
                          </button>
                        </div>
                      </div>
                    `;
                  } else {
                    return `
                      <div class="p-3 bg-white border border-[#dce8e2] rounded-2xl shadow-2xs space-y-1.5">
                        <div class="flex justify-between items-start">
                          <div class="text-xs font-bold text-[#163027]">离床完形填空</div>
                          <span class="text-[10px] px-2 py-0.5 rounded-full bg-[#fef4e8] text-[#8c531b] font-bold border border-[#fae5cc]">
                            跳过 ${item.skipCount} 次
                          </span>
                        </div>
                        <p class="text-xs text-[#2c4038] bg-[#f7faf8] p-2 rounded-2xl border border-[#edf3f0] leading-relaxed">
                          ${item.sentence}
                        </p>
                        <div class="text-[10px] text-[#1b5c45] font-semibold">正确答案：[ ${item.answer} ]</div>
                        <div class="flex justify-between items-center pt-1 text-[10px]">
                          <span class="text-[#738a82]">选项：${item.options.join(' / ')}</span>
                          <button onclick="removeBookmark('clozes', ${item.id})" class="text-[#738a82] hover:text-rose-600">
                            🗑️ 移出收藏
                          </button>
                        </div>
                      </div>
                    `;
                  }
                }).join('')}
              </div>
            </div>
          `;
          break;

        // 真实二级页面: 具体休息/临时放行时长申请页 (对应 window_rest_request.xml)
        case 'sub_rest_request':
          content.innerHTML = `
            <div class="h-full min-h-[660px] flex flex-col p-5 bg-white text-[#1c2e28]">
              <!-- 顶部返回 -->
              <div class="flex items-center justify-between pb-4">
                <button onclick="navTo(appState.previousScreen || 'home')" class="text-sm font-bold text-[#1b5c45] flex items-center gap-1">
                  ← 返回
                </button>
              </div>
              
              <div class="flex-1 flex flex-col justify-center items-center">
                <h2 class="text-[22px] font-bold text-[#333333] mb-6">申请休息 / 额度管理</h2>
                
                <p class="text-sm text-[#666666] mb-4">
                  当前可用: ${appState.remainingMinutes}min / ${appState.remainingCount}次 | 可借用: 10min / 1次
                </p>
                
                <p id="cooling_penalty" class="text-sm text-[#FF5252] font-bold mb-2 hidden">
                  ⚠️ 冷静期内申请将消耗双倍额度和次数！
                </p>
                
                <div class="w-full relative mb-6 mt-4">
                  <input type="number" id="input_rest_time" placeholder="输入申请时长 (分钟)" class="w-full border-b-2 border-[#999999] focus:border-[#1b5c45] outline-none text-center text-xl text-[#333333] py-2 transition-colors">
                </div>
                
                <div class="w-full flex gap-4 mt-2">
                  <button onclick="navTo(appState.previousScreen || 'home')" class="flex-1 py-3 bg-[#CCCCCC] text-white rounded-lg font-bold">
                    取消
                  </button>
                  <button onclick="confirmRestRequest()" class="flex-1 py-3 bg-[#FF9800] hover:bg-[#F57C00] text-white rounded-lg font-bold transition-colors shadow-md">
                    确认申请
                  </button>
                </div>
              </div>
            </div>
          `;
          break;


        // 锁屏 1: 勿扰锁定 (完全对齐图片)
        case 'lock_dnd':
          content.innerHTML = `
            <div class="h-full min-h-[660px] flex flex-col justify-between p-5 bg-[#eff5f2] text-[#1c2e28]">
              <div class="pt-4 text-center space-y-1.5">
                <div class="w-10 h-10 mx-auto rounded-2xl bg-[#deede6] flex items-center justify-center text-[#2d5f4e] mb-2">
                  <svg class="w-5 h-5 stroke-current fill-none" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"/></svg>
                </div>
                <h2 class="text-xl font-bold text-[#1a2d26] tracking-tight">勿扰锁定中</h2>
                <p class="text-xs text-[#526a61]">触发原因: 勿扰时段锁机 (抖音)</p>
                <p class="text-xs text-[#738a82]">上午时段 · 08:00—12:00</p>
              </div>

              <!-- 纯白精简账本 (已砍掉复杂的本段/结余分数明细) -->
              <div class="my-auto bg-white border border-[#dbe6e0] rounded-2xl p-4 shadow-sm space-y-3">
                <div class="flex justify-between items-center text-xs">
                  <span class="text-[#526a61] font-medium">当前剩余</span>
                  <span class="text-[10px] text-[#1b5c45] font-semibold">含滚存结余</span>
                </div>

                <div class="grid grid-cols-2 gap-2 py-0.5 border-b border-[#edf3f0]">
                  <div class="flex items-baseline gap-1">
                    <span class="text-3xl font-bold text-[#162e26] font-mono-num">${appState.remainingMinutes}</span>
                    <span class="text-xs text-[#526a61]">分钟</span>
                  </div>
                  <div class="flex items-baseline gap-1 justify-end">
                    <span class="text-3xl font-bold text-[#162e26] font-mono-num">${appState.remainingCount}</span>
                    <span class="text-xs text-[#526a61]">次</span>
                  </div>
                </div>

                <div class="bg-[#fef4e8] border border-[#fae5cc] rounded-2xl p-2.5 text-xs text-[#8c531b] space-y-0.5">
                  <div class="font-medium">已借 5 分钟 · 0.5 次</div>
                  <div class="text-[10px] text-[#9c6328]">下一段扣减 10 分钟 · 1 次</div>
                </div>

                <div class="pt-1 text-xs text-[#4e665d] flex justify-between">
                  <span>下一段扣减后剩余：</span>
                  <span class="font-bold text-[#162e26] font-mono-num">50 分钟 · 3 次</span>
                </div>
              </div>

              <div class="space-y-2 pb-2">
                <button onclick="navTo('home')" class="w-full py-3.5 bg-white hover:bg-[#f7faf8] border border-[#d0dfd8] text-[#1a2d26] rounded-full text-xs font-semibold shadow-2xs">
                  回到桌面
                </button>
                <button onclick="promptApplyRest()" class="w-full py-3.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-full text-xs font-bold shadow-sm">
                  申请休息
                </button>
              </div>
            </div>
          `;
          break;

        // 锁屏 2: 应用时间窗限制
        case 'lock_app':
          content.innerHTML = `
            <div class="h-full min-h-[660px] flex flex-col justify-between p-5 bg-[#eff5f2] text-[#1c2e28]">
              <div class="pt-4 text-center space-y-1.5">
                <div class="w-10 h-10 mx-auto rounded-2xl bg-[#dbeaf2] flex items-center justify-center text-[#216583] mb-2">
                  <svg class="w-5 h-5 stroke-current fill-none" stroke-width="2" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
                </div>
                <h2 class="text-xl font-bold text-[#1a2d26] tracking-tight">应用时间窗限制</h2>
                <p class="text-xs text-[#526a61]">抖音当前不可用</p>
                <p class="text-xs text-[#738a82]">今日允许使用: 21:00—22:30</p>
              </div>

              <div class="my-auto bg-white border border-[#dbe6e0] rounded-2xl p-4 shadow-sm space-y-3">
                <div class="flex justify-between items-center text-xs">
                  <span class="text-[#526a61] font-medium">当前剩余</span>
                  <span class="text-[10px] text-[#1b5c45] font-semibold">含滚存结余</span>
                </div>

                <div class="grid grid-cols-2 gap-2 py-0.5 border-b border-[#edf3f0]">
                  <div class="flex items-baseline gap-1">
                    <span class="text-3xl font-bold text-[#162e26] font-mono-num">${appState.remainingMinutes}</span>
                    <span class="text-xs text-[#526a61]">分钟</span>
                  </div>
                  <div class="flex items-baseline gap-1 justify-end">
                    <span class="text-3xl font-bold text-[#162e26] font-mono-num">${appState.remainingCount}</span>
                    <span class="text-xs text-[#526a61]">次</span>
                  </div>
                </div>

                <div class="bg-[#fef4e8] border border-[#fae5cc] rounded-2xl p-2.5 text-xs text-[#8c531b] space-y-0.5">
                  <div class="font-medium">已借 5 分钟 · 0.5 次</div>
                  <div class="text-[10px] text-[#9c6328]">下一段扣减 10 分钟 · 1 次</div>
                </div>

                <div class="pt-1 text-xs text-[#4e665d] flex justify-between">
                  <span>下一段扣减后剩余：</span>
                  <span class="font-bold text-[#162e26] font-mono-num">50 分钟 · 3 次</span>
                </div>
              </div>

              <div class="space-y-2 pb-2">
                <button onclick="navTo('home')" class="w-full py-3.5 bg-white hover:bg-[#f7faf8] border border-[#d0dfd8] text-[#1a2d26] rounded-full text-xs font-semibold shadow-2xs">
                  回到桌面
                </button>
                <button onclick="triggerTempAccess()" class="w-full py-3.5 bg-[#1a3830] hover:bg-[#132b25] text-white rounded-full text-xs font-bold shadow-sm">
                  申请特权
                </button>
              </div>
            </div>
          `;
          break;
      }
    }

    // 交互行为函数
    function toggleAlarm(id) {
      const alarm = appState.alarms.find(a => a.id === id);
      if (alarm) {
        alarm.enabled = !alarm.enabled;
        showToast(alarm.enabled ? '闹钟已开启' : '闹钟已关闭');
        renderCurrentScreen();
      }
    }

    function toggleAppSelection(pkg) {
      const idx = appState.selectedApps.indexOf(pkg);
      if (idx >= 0) {
        appState.selectedApps.splice(idx, 1);
      } else {
        appState.selectedApps.push(pkg);
      }
      renderCurrentScreen();
    }

    function toggleListMode() {
      appState.listMode = appState.listMode === 'blacklist' ? 'whitelist' : 'blacklist';
      showToast(`已切换为：${appState.listMode === 'blacklist' ? '黑名单模式' : '白名单模式'}`);
      renderCurrentScreen();
    }

    function saveAppSelection() {
      showToast(`黑白名单保存成功！当前管理 ${appState.selectedApps.length} 款`);
      navTo('settings');
    }

    // 新建闹钟弹窗
    function openAlarmEditModal() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="flex justify-between items-center border-b border-[#edf3f0] pb-2">
            <span class="font-bold text-sm text-[#163027]">新建起床闹钟</span>
            <button onclick="closeModal()" class="text-[#738a82] text-sm">✕</button>
          </div>

          <!-- 时间选择 -->
          <div class="text-center py-2 bg-[#f4f8f6] rounded-2xl">
            <div class="text-3xl font-bold font-mono-num text-[#163027]">07:30</div>
            <div class="text-[10px] text-[#5c736a]">点击上下微调时间</div>
          </div>

          <!-- 星期重复 -->
          <div>
            <div class="font-semibold text-[#163027] mb-1.5">重复周期</div>
            <div class="flex justify-between gap-1">
              ${['一','二','三','四','五','六','日'].map((day, i) => `
                <button class="w-8 h-8 rounded-lg text-xs font-bold ${i < 5 ? 'bg-[#1a3830] text-white' : 'bg-[#f0f6f3] text-[#5c736a]'}">
                  ${day}
                </button>
              `).join('')}
            </div>
          </div>

          <!-- 解锁挑战方式 -->
          <div>
            <div class="font-semibold text-[#163027] mb-1.5">离床解锁挑战</div>
            <div class="space-y-1">
              <label class="flex items-center gap-2 p-2 rounded-2xl border border-[#1b5c45] bg-[#f0f8f4] cursor-pointer">
                <input type="radio" name="ch" checked class="accent-[#1b5c45]">
                <span class="font-bold text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"></path><circle cx="12" cy="13" r="4"></circle></svg>扫描浴室二维码 (强制下床)</span>
              </label>
              <label class="flex items-center gap-2 p-2 rounded-2xl border border-[#edf3f0] text-[#5c736a] cursor-pointer">
                <input type="radio" name="ch" class="accent-[#1b5c45]">
                <span>🧮 进阶数学算术 3 道</span>
              </label>
            </div>
          </div>

          <!-- 防回马枪复核开关 -->
          <div class="flex justify-between items-center p-2 rounded-2xl bg-[#f7faf8] border border-[#e2ede7]">
            <div>
              <div class="font-bold text-[#163027]">双阶段防回马枪</div>
              <div class="text-[9px] text-[#5c736a]">关闭后 4 分钟发起二次洗漱复核</div>
            </div>
            <div class="w-9 h-5 bg-[#1a3830] rounded-full p-0.5 flex items-center justify-end"><div class="w-4 h-4 bg-white rounded-full"></div></div>
          </div>

          <!-- 保存按钮 -->
          <div class="flex gap-2 pt-1">
            <button onclick="closeModal()" class="flex-1 py-2 bg-white border border-[#d0dfd8] text-[#1a2d26] rounded-2xl font-semibold">取消</button>
            <button onclick="saveNewAlarm()" class="flex-1 py-2 bg-[#1a3830] text-white rounded-2xl font-bold">保存闹钟</button>
          </div>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    function closeModal() {
      document.getElementById('modal-container').classList.add('hidden');
    }

    function saveNewAlarm() {
      appState.alarms.push({
        id: Date.now(),
        time: '07:30',
        label: '自习闹钟',
        repeat: '周一至周五',
        challenge: `<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"></path><circle cx="12" cy="13" r="4"></circle></svg>浴室远处二维码`,
        antiRelapse: true,
        enabled: true
      });
      closeModal();
      showToast('新闹钟已保存并生效！');
      navTo('alarm');
    }

    // 离床错题收藏夹业务函数 (对应 activity_bookmark.xml)
    function openBookmarkTab(tab) {
      appState.bookmarkTab = tab;
      navTo('sub_bookmark');
    }

    function switchBookmarkTab(tab) {
      appState.bookmarkTab = tab;
      renderCurrentScreen();
    }

    function searchBookmark(val) {
      appState.bookmarkSearch = val;
      renderCurrentScreen();
    }

    function setBookmarkSort(by) {
      appState.bookmarkSortBy = by;
      renderCurrentScreen();
    }

    function setBookmarkRange(r) {
      appState.bookmarkRange = r;
      showToast(`已切换时间范围：${r === 'all' ? '全部' : '近' + r}`);
      renderCurrentScreen();
    }

    function removeBookmark(tab, id) {
      appState.bookmarks[tab] = appState.bookmarks[tab].filter(it => it.id !== id);
      showToast('已从收藏夹移出该题目');
      renderCurrentScreen();
    }

    // 闹钟响铃挑战测试 (模拟 window_alarm_overlay.xml 真实两行布局)
    function testAlarmChallengeModal() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="flex justify-between items-center border-b border-[#edf3f0] pb-2">
            <span class="font-bold text-sm text-[#163027]"><svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path><path d="M13.73 21a2 2 0 0 1-3.46 0"></path></svg>响铃离床挑战模拟</span>
            <button onclick="closeModal()" class="text-[#738a82] text-sm">✕</button>
          </div>
          
          <div class="text-center py-2 bg-[#f4f8f6] rounded-2xl">
            <div class="text-xs font-bold text-[#1b5c45]">离床答题：选词填空 (1/2)</div>
            <p class="text-xs text-[#163027] font-medium mt-2 px-2 leading-relaxed">
              "Diligent practice brings inner [____] in times of chaos."
            </p>
          </div>

          <div class="space-y-1.5 pt-1">
            <button onclick="showToast('回答正确！挑战进度 1/2')" class="w-full py-2 bg-white border border-[#dce8e2] hover:border-[#1b5c45] rounded-2xl text-xs font-bold text-[#163027] text-left px-3">
              A. peace (平静)
            </button>
            <button onclick="showToast('回答错误，请重试')" class="w-full py-2 bg-white border border-[#dce8e2] rounded-2xl text-xs font-medium text-[#5c736a] text-left px-3">
              B. panic (恐慌)
            </button>
            <button onclick="showToast('回答错误，请重试')" class="w-full py-2 bg-white border border-[#dce8e2] rounded-2xl text-xs font-medium text-[#5c736a] text-left px-3">
              C. delay (延误)
            </button>
          </div>

          <!-- 核心两行操作按钮 (对应 window_alarm_overlay.xml 真实功能) -->
          <div class="grid grid-cols-2 gap-2 pt-2 border-t border-[#edf3f0]">
            <button onclick="bookmarkAndSkipCurrent()" class="py-2 bg-[#fef4e8] border border-[#fae5cc] text-[#8c531b] rounded-2xl font-bold text-xs flex items-center justify-center gap-1">
              <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>收藏并跳过
            </button>
            <button onclick="closeModal(); showToast('离床挑战完成，闹钟已关闭');" class="py-2 bg-[#1a3830] text-white rounded-2xl font-bold text-xs">
              提交完成
            </button>
          </div>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    function bookmarkAndSkipCurrent() {
      appState.bookmarks.clozes.push({
        id: Date.now(),
        sentence: 'Diligent practice brings inner [____] in times of chaos.',
        answer: 'peace',
        options: ['peace', 'panic', 'delay'],
        skipCount: 1,
        date: '2026-09-05'
      });
      closeModal();
      showToast(`<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>已加入错题收藏夹，已为您切换到下一题！`);
      renderCurrentScreen();
    }

    // 通用防冲动契约弹窗 (宽严有别：加严立即，放宽次日 08:00 生效)
    function promptAntiImpulseContract(title, desc, onConfirm) {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#fae5cc] shadow-2xl space-y-3 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="w-10 h-10 mx-auto rounded-2xl bg-[#fef4e8] flex items-center justify-center text-xl text-[#8c531b]">
            <svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
          </div>
          <div class="text-center">
            <h3 class="font-bold text-sm text-[#163027]">${title}</h3>
            <p class="text-[11px] text-[#8c531b] mt-1 leading-relaxed">
              “决定守护防的不是解除，是那一瞬间想放弃自律的冲动。你今天说想走，明天就能走。”
            </p>
          </div>

          <div class="bg-[#fef8f0] p-3 rounded-2xl border border-[#fae5cc] text-[10px] text-[#8a5b28] space-y-1">
            <div>• <strong>延迟冷静期</strong>：申请后将于<strong>明日 08:00 基准时刻</strong>正式解除。</div>
            <div>• <strong>当前仍拦截</strong>：${desc}在明日生效前继续保持保护，绝不姑息冲动。</div>
            <div>• <strong>绝不流氓绑架</strong>：明日 08:00 准时落地解除，保障您的终极自由。</div>
          </div>

          <div class="flex gap-2 pt-1">
            <button onclick="closeModal()" class="flex-1 py-2 bg-[#1a3830] text-white rounded-2xl font-bold">保持保护 (取消)</button>
            <button onclick="${onConfirm}" class="flex-1 py-2 bg-white border border-[#fae5cc] text-[#8c531b] rounded-2xl font-semibold">确认申请次日解除</button>
          </div>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    
    function endAdaptationPeriod() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="text-center font-bold text-sm text-[#163027] mb-2">结束适应期</div>
          <p class="text-[#5c736a] text-center leading-relaxed">
            确认要结束适应期吗？结束后，所有的延时惩罚、锁屏等强制拦截功能将<strong class="text-rose-600">真正生效</strong>，不再提供快捷关闭通道！
          </p>
          <div class="flex gap-2 pt-2">
            <button onclick="closeModal()" class="flex-1 py-2 bg-white border border-[#d0dfd8] text-[#1a2d26] rounded-2xl font-semibold">再想想</button>
            <button onclick="confirmEndAdaptation()" class="flex-1 py-2 bg-[#1a3830] text-white rounded-2xl font-bold">我准备好了</button>
          </div>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    function confirmEndAdaptation() {
      appState.inAdaptation = false;
      closeModal();
      showToast('🚀 已进入严格模式，愿你找回专注！');
      renderCurrentScreen();
    }

    function showModalWithMessage(title, msg) {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="font-bold text-sm text-[#163027]">${title}</div>
          <p class="text-[#5c736a] leading-relaxed">${msg}</p>
          <button onclick="closeModal()" class="w-full py-2 mt-2 bg-[#1a3830] text-white rounded-2xl font-bold">知道了</button>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    function openSnoozeSettingsModal() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-t-3xl rounded-b-lg p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs absolute bottom-4 animate-spring-up">
          <div class="font-bold text-sm text-[#163027] border-b border-[#edf3f0] pb-2">闹钟再响设置</div>
          <div class="space-y-1 pt-1">
            <label class="flex items-center justify-between p-2 rounded-xl bg-[#f0f6f3] cursor-pointer">
              <span class="font-bold text-[#1a3830]">间隔 5 分钟，最多 3 次</span>
              <input type="radio" name="snz" value="5分钟/3次" class="accent-[#1b5c45]" ${appState.snoozeConfig === '5分钟/3次' ? 'checked' : ''}>
            </label>
            <label class="flex items-center justify-between p-2 rounded-xl bg-[#f0f6f3] cursor-pointer">
              <span class="font-bold text-[#1a3830]">间隔 10 分钟，最多 2 次</span>
              <input type="radio" name="snz" value="10分钟/2次" class="accent-[#1b5c45]" ${appState.snoozeConfig === '10分钟/2次' ? 'checked' : ''}>
            </label>
          </div>
          <button onclick="saveSnoozeConfig()" class="w-full py-2.5 mt-2 bg-[#1a3830] text-white rounded-2xl font-bold">确认保存</button>
        </div>
      `;
      modal.classList.remove('hidden');
    }

    function saveSnoozeConfig() {
      const val = document.querySelector('input[name="snz"]:checked').value;
      appState.snoozeConfig = val;
      closeModal();
      showToast('再响设置已保存');
      renderCurrentScreen();
    }

    function openKeepAliveModal() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3.5 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="font-bold text-sm text-[#163027] mb-1">闹钟保活机制</div>
          <p class="text-[#5c736a] leading-relaxed bg-[#f4f8f6] p-2 rounded-xl border border-[#edf3f0]">
            由于安卓系统杀后台严重，开启此项将申请前台服务常驻，并配置 Direct Boot (手机重启密码未解开前依然可响铃)。
          </p>
          <label class="flex items-center justify-between p-2 mt-2 border border-[#dce8e2] rounded-xl cursor-pointer">
            <span class="font-bold text-[#163027]">开启强效保活</span>
            <input type="checkbox" id="keepAliveChk" class="accent-[#1b5c45]" ${appState.keepAlive ? 'checked' : ''}>
          </label>
          <button onclick="saveKeepAlive()" class="w-full py-2 bg-[#1a3830] text-white rounded-2xl font-bold">确定</button>
        </div>
      `;
      modal.classList.remove('hidden');
    }
    function saveKeepAlive() {
      appState.keepAlive = document.getElementById('keepAliveChk').checked;
      closeModal();
      showToast('保活配置已更新');
      renderCurrentScreen();
    }

    function openChallengeConfigModal() {
      const modal = document.getElementById('modal-container');
      modal.innerHTML = `
        <div class="bg-white rounded-3xl p-5 w-full max-w-sm border border-[#dce8e2] shadow-2xl space-y-3 text-xs animate-in fade-in zoom-in-95 duration-150">
          <div class="font-bold text-sm text-[#163027] border-b border-[#edf3f0] pb-2">默认离床挑战数量</div>
          <div class="space-y-2 pt-1">
            <div>
              <div class="text-[10px] text-[#5c736a] mb-1">数学题数量</div>
              <div class="flex gap-2">
                <button onclick="setTempChal(this, 'math')" class="flex-1 py-1 rounded border border-[#1b5c45] bg-[#eaf3ef] font-bold text-[#1b5c45]">2道</button>
                <button onclick="setTempChal(this, 'math')" class="flex-1 py-1 rounded border border-[#d0dfd8] text-[#5c736a]">3道</button>
                <button onclick="setTempChal(this, 'math')" class="flex-1 py-1 rounded border border-[#d0dfd8] text-[#5c736a]">5道</button>
              </div>
            </div>
            <div>
              <div class="text-[10px] text-[#5c736a] mb-1">诗词/单词题数量</div>
              <div class="flex gap-2">
                <button onclick="setTempChal(this, 'word')" class="flex-1 py-1 rounded border border-[#d0dfd8] text-[#5c736a]">2句</button>
                <button onclick="setTempChal(this, 'word')" class="flex-1 py-1 rounded border border-[#1b5c45] bg-[#eaf3ef] font-bold text-[#1b5c45]">3句</button>
                <button onclick="setTempChal(this, 'word')" class="flex-1 py-1 rounded border border-[#d0dfd8] text-[#5c736a]">5句</button>
              </div>
            </div>
          </div>
          <button onclick="saveChallengeConfig()" class="w-full py-2 mt-3 bg-[#1a3830] text-white rounded-2xl font-bold">确认保存</button>
        </div>
      `;
      modal.classList.remove('hidden');
    }
    
    function setTempChal(btn, type) {
      Array.from(btn.parentElement.children).forEach(b => {
        b.className = "flex-1 py-1 rounded border border-[#d0dfd8] text-[#5c736a]";
      });
      btn.className = "flex-1 py-1 rounded border border-[#1b5c45] bg-[#eaf3ef] font-bold text-[#1b5c45]";
      btn.parentElement.dataset.val = btn.innerText;
    }
    
    function saveChallengeConfig() {
      // Mock save
      appState.challengeConfig = "自定义配置";
      closeModal();
      showToast('挑战数量配置已更新');
      renderCurrentScreen();
    }

    function promptApplyRest() {
      appState.previousScreen = appState.currentScreen;
      navTo('sub_rest_request');
    }

    function confirmRestRequest() {
      const input = document.getElementById('input_rest_time');
      const mins = parseInt(input.value);
      if(isNaN(mins) || mins <= 0) {
        showToast('请输入有效的时长！');
        return;
      }
      if(appState.remainingMinutes < mins) {
        showToast('⚠️ 剩余额度不足，将自动转为借用惩罚机制。');
        // Let them proceed anyway for simulation
      }
      appState.remainingMinutes = Math.max(0, appState.remainingMinutes - mins);
      appState.remainingCount = Math.max(0, appState.remainingCount - 1);
      showToast(`✅ 成功申请 ${mins} 分钟休息时间`);
      navTo(appState.previousScreen || 'home');
    }

    function triggerTempAccess() {
      appState.previousScreen = appState.currentScreen;
      navTo('sub_rest_request');
      // Simulate it automatically selecting <60s context
      setTimeout(() => {
         const title = document.querySelector('h2.text-\[22px\]');
         if(title) title.innerText = '超级临时放行 (<60s)';
         const input = document.getElementById('input_rest_time');
         if(input) {
             input.placeholder = '输入申请时长 (秒)';
         }
      }, 50);
    }

    
    function toggleAppWindowRule(id) {
      const rule = appState.appWindowRules.find(r => r.id === id);
      if (rule) {
        rule.enabled = !rule.enabled;
        showToast(`规则 ${rule.name} 已${rule.enabled ? '开启' : '关闭'}`);
        renderCurrentScreen();
      }
    }
    
    function deleteAppWindowRule(id) {
      appState.appWindowRules = appState.appWindowRules.filter(r => r.id !== id);
      showToast('规则已删除');
      renderCurrentScreen();
    }
    
    function editAppWindowRule(id) {
      appState.editingRuleId = id;
      navTo('sub_edit_app_window');
    }
    
    function saveAppWindowRule() {
      const name = document.getElementById('ruleName').value || '未命名规则';
      if (appState.editingRuleId) {
        const rule = appState.appWindowRules.find(r => r.id === appState.editingRuleId);
        rule.name = name;
      } else {
        appState.appWindowRules.push({
          id: Date.now(),
          name: name,
          start: '10:00', end: '12:00',
          days: [1,2,3,4,5], apps: ['com.tencent.mm'], enabled: true
        });
      }
      showToast('规则已保存');
      navTo('sub_app_window');
    }

    // 1. 无障碍服务开关
    function toggleA11yService() {
      if (appState.a11yEnabled) {
        promptAntiImpulseContract('申请关闭无障碍服务？', '无障碍前台监控与拦截服务', 'confirmDisableA11y()');
      } else {
        appState.a11yEnabled = true;
        showToast('无障碍防护已重新激活！');
        renderCurrentScreen();
      }
    }

    function confirmDisableA11y() {
      appState.pendingA11yDisable = true;
      closeModal();
      showToast('已登记无障碍解除申请，将于明日 08:00 正式解除！');
      renderCurrentScreen();
    }

    // 2. 防卸载保护开关
    function toggleAntiUninstall() {
      if (appState.antiUninstallEnabled && !appState.pendingAntiUninstallDisable) {
        promptAntiImpulseContract('申请解除防卸载保护？', '拦截系统应用信息页与安装器', 'confirmDisableAntiUninstall()');
      } else if (appState.pendingAntiUninstallDisable) {
        showToast('您已处于申请解除中，将于明日 08:00 正式生效');
      } else {
        appState.antiUninstallEnabled = true;
        showToast('防卸载保护已立即开启！');
        renderCurrentScreen();
      }
    }

    function confirmDisableAntiUninstall() {
      appState.pendingAntiUninstallDisable = true;
      closeModal();
      showToast('已登记防卸载解除申请，将于明日 08:00 正式落地！');
      renderCurrentScreen();
    }

    // 3. 防关权限保护开关
    function toggleAntiRevoke() {
      if (appState.antiRevokePermEnabled && !appState.pendingAntiRevokeDisable) {
        promptAntiImpulseContract('申请解除防关权限保护？', '拦截系统设置中关闭辅助功能', 'confirmDisableAntiRevoke()');
      } else if (appState.pendingAntiRevokeDisable) {
        showToast('您已处于申请解除中，将于明日 08:00 正式生效');
      } else {
        appState.antiRevokePermEnabled = true;
        showToast('防关权限保护已立即开启！');
        renderCurrentScreen();
      }
    }

    function confirmDisableAntiRevoke() {
      appState.pendingAntiRevokeDisable = true;
      closeModal();
      showToast('已登记防关权限解除申请，将于明日 08:00 正式落地！');
      renderCurrentScreen();
    }

    // 4. 隐私空间拦截开关
    function togglePrivacyGuard() {
      if (!appState.a11yEnabled) {
        showToast('开启前置提示：必须先激活无障碍服务');
        return;
      }
      if (appState.privacySpaceGuardEnabled && !appState.pendingPrivacyGuardDisable) {
        promptAntiImpulseContract('申请关闭隐私空间拦截？', '拦截系统分身与双开入口', 'confirmDisablePrivacyGuard()');
      } else if (appState.pendingPrivacyGuardDisable) {
        showToast('您已处于申请解除中，将于明日 08:00 正式生效');
      } else {
        appState.privacySpaceGuardEnabled = true;
        showToast('隐私空间拦截已立即开启！');
        renderCurrentScreen();
      }
    }

    function confirmDisablePrivacyGuard() {
      appState.pendingPrivacyGuardDisable = true;
      closeModal();
      showToast('已登记隐私空间拦截关闭申请，将于明日 08:00 正式落地！');
      renderCurrentScreen();
    }

    // 5. 闹钟防关机保护开关
    function toggleAlarmPowerOffProtection() {
      if (!appState.a11yEnabled) {
        showToast('开启前置提示：闹钟防关机需依赖无障碍服务');
        return;
      }
      appState.alarmPowerOffProtection = !appState.alarmPowerOffProtection;
      showToast(appState.alarmPowerOffProtection ? '闹钟防关机保护已开启 (响铃期拦截长按电源键)' : '闹钟防关机已关闭');
      renderCurrentScreen();
    }

    // 6. 在多任务界面中隐藏决定守护开关
    function toggleHideInRecentTasks() {
      appState.hideInRecentTasks = !appState.hideInRecentTasks;
      showToast(appState.hideInRecentTasks ? '已在多任务界面中隐藏决定守护' : '已恢复在多任务界面中显示决定守护');
      renderCurrentScreen();
    }

    function promptApplyRest() {
      showToast('已申请消耗 15 分钟休息额度，锁屏已临时放行');
      navTo('home');
    }

    function triggerTempAccess() {
      showToast('已启用超级临时放行 (倒计时 60 秒开始)');
    }

    // 右侧解释卡片更新
    function updateExplanationCard(screenId) {
      const el = document.getElementById('explanation-card');
      const guides = {
        home: {
          title: "主页 · 纯白清爽双轨账本",
          sub: "对应 fragment_home.xml 真实核心组件",
          points: [
            "大字呈现当前总剩余（25分钟/2次），次级清晰严谨展示「本段15/30分钟」与「结余10/20分钟」双轨来源。",
            "暖杏色卡片突出借用惩罚：已借5分，下一段双倍扣减10分，真实算力透明可溯。",
            "保留了所有真实业务控件：适应期、服务守护卡、每周特权与使用说明。"
          ]
        },
        rules: {
          title: "专注规则 · 真实勿扰时段与延迟生效",
          sub: "对应 fragment_time_config.xml 真实时段列表",
          points: [
            "列表仅展示用户配置的勿扰时段实体（晨间、下午、晚间），两个时段中间的空白由自然时间差体现，绝无虚构卡片。",
            "暖杏色卡片常驻呈现「延迟生效表」，明确告知加严立即、放宽次日的规则。",
            "底部还原添加时段、批量周期与一键方案真实操作按键。"
          ]
        },
        stats: {
          title: "统计 · 周/月/年与图表分析",
          sub: "对应 fragment_statistics.xml 真实统计指标",
          points: [
            "真实呈现单选框「● 休息时长」与「○ 自律时长」，支持周/月/年三段切换。",
            "复选框支持「☑ 加权」与换算表点击提示，柱状图如实映射每日消耗。",
            "仅展示真实周度总计与日均休息时长，彻底杜绝虚假编造的指标。"
          ]
        },
        alarm: {
          title: "闹钟 · 离床挑战与错题收藏夹",
          sub: "对应 fragment_alarm.xml 完整功能集合",
          points: [
            `点击「<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path><path d="M13.73 21a2 2 0 0 1-3.46 0"></path></svg>测试响铃挑战」可立即弹出高保真响铃答题弹窗，支持做题与点击「<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>收藏并跳过」！`,
            "底部 3 大收藏本按钮直通「BookmarkActivity」：诗词、单词、选词填空错题本，点击任意一个即可进入。",
            "收纳了再响设置、闹钟保活(Direct Boot)、应急应用与默认挑战数量卡片。"
          ]
        },
        settings: {
          title: "设置 · 6大无障碍约束守护体系",
          sub: "对应 fragment_rules.xml 真实约束大框",
          points: [
            "全量还原 5 大无障碍核心约束开关（无障碍、防卸载、防关权限、隐私空间、闹钟防关机）及多任务隐藏。",
            "每个开关均支持真实交互：尝试关闭均会弹出决定守护著名的「次日 08:00 解除」防冲动冷静期契约弹窗！",
            "保留每周特权卡片、关于我们（7项法务协议）及日志诊断（LogExporter）备份入口。"
          ]
        },
        sub_bookmark: {
          title: "二级页面：离床错题收藏夹 (BookmarkActivity)",
          sub: "对齐 activity_bookmark.xml 真实界面设计",
          points: [
            `支持 3 大 Tab 快速切换：<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>诗词收藏、<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"></path><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"></path></svg>单词收藏、<svg class="w-4 h-4 inline-block mr-1 -mt-0.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"></path></svg>选词填空收藏。`,
            "支持在输入框内按关键词即时搜索诗词句、单词拼写与释义。",
            "支持按「跳过次数（错题优先）」与「收藏时间」排序，并支持近7天/30天/90天时间过滤。"
          ]
        },
        sub_app_selection: {
          title: "二级页面：黑白名单应用选择 (AppSelectionActivity)",
          sub: "点击任意应用卡片可直接勾选/取消勾选，并保存生效",
          points: [
            "支持切换「黑名单模式」与「白名单模式」。",
            "内置搜索栏与应用标签（短视频、游戏、社交）。",
            "底部按钮实时统计当前已勾选的应用数量。"
          ]
        },
        sub_global_params: {
          title: "二级页面：全局参数配置 (GlobalParamsActivity)",
          sub: "配置每日基准刷新时间、借用上限与惩罚倍率",
          points: [
            "每日基准时刻：默认 08:00，所有放宽变更延迟至此生效。",
            "借用上限比例：支持切换 30% / 50% / 80%。",
            "点击保存后自动记录并返回设置页。"
          ]
        },
        lock_dnd: {
          title: "锁屏 1：勿扰锁定中 (完全对齐您提供的图片)",
          sub: "用户在专注时段打开抖音被拦截",
          points: [
            "浅绿盾牌徽标 + 明确触发原因与时间段。",
            "已应用精简账本：只保留核心可用余额与借用惩罚，消除繁复分数。",
            "白底「回到桌面」+ 墨绿「申请休息」。"
          ]
        },
        lock_app: {
          title: "锁屏 2：应用时间窗限制 (完全对齐您提供的图片)",
          sub: "用户在非娱乐时段打开抖音被拦截",
          points: [
            "浅蓝时钟徽标 + 明确提示「今日允许使用: 21:00—22:30」。",
            "白底「回到桌面」+ 墨绿「申请特权」。"
          ]
        }
      };

      const g = guides[screenId] || guides.home;
      el.innerHTML = `
        <div class="flex items-center justify-between mb-2">
          <h2 class="text-base font-bold text-[#163027]">${g.title}</h2>
          <span class="text-xs px-2.5 py-1 rounded-lg bg-[#eff5f2] text-[#1b5c45] font-mono border border-[#d8e5df]">可操作</span>
        </div>
        <p class="text-xs text-[#526a61] mb-3">${g.sub}</p>
        <div class="space-y-1.5 pt-2 border-t border-[#edf3f0]">
          ${g.points.map(p => `<div class="text-xs text-[#4e665d] flex items-start gap-1.5"><span class="text-[#1b5c45] font-bold">✓</span><span>${p}</span></div>`).join('')}
        </div>
      `;
    }

    // 初始化渲染
    window.addEventListener('DOMContentLoaded', () => {
      navTo('home');
    });
  