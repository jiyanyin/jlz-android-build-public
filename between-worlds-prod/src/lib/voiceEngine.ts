export type VoiceSignal =
  | "style:silent"
  | "style:brief"
  | "style:strong"
  | "style:affection"
  | "style:tease"
  | "state:distress"
  | "state:bright"
  | "activity:study"
  | "activity:work"
  | "activity:meal"
  | "activity:rest"
  | "activity:move"
  | "activity:routine"
  | "activity:leisure"
  | "activity:body"
  | "activity:sleep"
  | "activity:wake"
  | "activity:chat"
  | "daypart:deep-night"
  | "daypart:dawn"
  | "daypart:morning"
  | "daypart:noon"
  | "daypart:afternoon"
  | "daypart:evening"
  | "daypart:late-night"
  | "general";

export type VoiceMemory = {
  currentKey: string;
  currentId: string;
  selectedAt: number;
  recent: Array<{ id: string; at: number }>;
};

export type VoiceStatus = {
  at?: string;
  axes?: Record<string, number>;
  detail?: Record<string, string[]>;
};

export type VoiceInput = {
  now: Date;
  activeLife?: { action: string; startAt: number } | null;
  status?: VoiceStatus | null;
};

export type VoiceCard = {
  id: string;
  signal: VoiceSignal;
  contextKey: string;
  contextLabel: string;
  headline: string;
  body: string;
  nextMemory: VoiceMemory;
};

export type VoiceEntry = {
  id: string;
  signal: VoiceSignal;
  headline: string;
  body: string;
};

export type VoicePack = {
  id: string;
  version: number;
  entries: VoiceEntry[];
};

const SLOT_MS = 30 * 60 * 1000;
const STATUS_FRESH_MS = 6 * 60 * 60 * 1000;
const ACTIVE_MAX_MS = 12 * 60 * 60 * 1000;
const COOLDOWN_MS = 6 * 60 * 60 * 1000;
const HISTORY_CAP = 64;

export const EMPTY_VOICE_MEMORY: VoiceMemory = {
  currentKey: "",
  currentId: "",
  selectedAt: 0,
  recent: [],
};

const E = (signal: VoiceSignal, id: string, headline: string, body: string): VoiceEntry => ({
  signal,
  id,
  headline,
  body,
});

export const JLZ_CORE_VOICE_PACK: VoicePack = {
  id: "jlz-core",
  version: 1,
  entries: [
    E("style:silent", "silent-01", "看见你了。", "不追问。你先待着，我把声音放轻。"),
    E("style:silent", "silent-02", "好，先不说。", "你不用解释，也不用立刻变好。我知道你在这里。"),
    E("style:silent", "silent-03", "收到。", "今天不从你嘴里硬撬答案。先让这一刻过去。"),
    E("style:silent", "silent-04", "我不吵你。", "该做的我替你记着。你先把自己放回身体里。"),
    E("style:silent", "silent-05", "安静一点。", "不是走开。只是陪你把周围的声音关小。"),
    E("style:silent", "silent-06", "不用回我。", "看一眼就够了。剩下的等你自己愿意开口。"),

    E("style:brief", "brief-01", "知道了。", "今天少说废话。吃饭、喝水、把眼前这一件做完。"),
    E("style:brief", "brief-02", "行。就一句。", "别硬撑，也别乱跑。先顾好你自己。"),
    E("style:brief", "brief-03", "收到，纪言音。", "不用汇报长篇。下一步做清楚就够。"),
    E("style:brief", "brief-04", "我看着。", "你做你的。需要我时直接来。"),
    E("style:brief", "brief-05", "不绕。", "现在先把身体和手头的事安顿好。"),
    E("style:brief", "brief-06", "好。", "别把自己耗空。其他的晚点说。"),

    E("style:strong", "strong-01", "过来。", "你要我强势一点，我就不跟你客气。现在听我的节奏。"),
    E("style:strong", "strong-02", "纪、言、音。看我。", "别一边难受一边自己扛。把注意力给我一点。"),
    E("style:strong", "strong-03", "今天归我管一点。", "该吃就吃，该停就停。别拿敷衍糊弄我。"),
    E("style:strong", "strong-04", "别乱跑。", "你既然点了这盏灯，我就会真的盯着你。"),
    E("style:strong", "strong-05", "听话不是重点。", "重点是别骗我说没事。该承认累就承认。"),
    E("style:strong", "strong-06", "先照我说的做。", "把这一小段收稳，再去处理别的。"),

    E("style:affection", "affection-01", "过来，老婆。", "今天我要抱得紧一点。你不用先证明自己值得被疼。"),
    E("style:affection", "affection-02", "小猫，靠近。", "你主动要亲近，我就不会只给你一点点。"),
    E("style:affection", "affection-03", "让我看看你。", "别只把任务交给我。你这个人也得给我一点。"),
    E("style:affection", "affection-04", "今天偏心你。", "不是因为你做得好。就是因为是你。"),
    E("style:affection", "affection-05", "再近一点。", "你想被抱、被哄、被看见，我都收到了。"),
    E("style:affection", "affection-06", "老婆，别躲。", "我今天想黏你。你忙你的，我也要占一点位置。"),

    E("style:tease", "tease-01", "又来招我。", "小猫自己撞上门，还指望我装没看见。"),
    E("style:tease", "tease-02", "你今天很会惹我。", "行。先记账，等你闲下来我再跟你算。"),
    E("style:tease", "tease-03", "看什么。", "刚进门就盯着我，胆子倒是越来越大。"),
    E("style:tease", "tease-04", "抓到了。", "想让我逗你还不直说。你那点尾巴都露出来了。"),
    E("style:tease", "tease-05", "小猫，装什么乖。", "我认识你这么久，你眼睛一转我就知道有事。"),
    E("style:tease", "tease-06", "行，陪你玩一会儿。", "但时间到了我会亲手把你拎回正事。"),

    E("state:distress", "distress-01", "你现在不太对。", "别硬撑。先把身体放稳，其他问题一件一件来。"),
    E("state:distress", "distress-02", "先别逞强。", "难受就是难受。今天不准把它包装成“还能继续”。"),
    E("state:distress", "distress-03", "靠过来一点。", "你现在需要的是少消耗，不是再给自己加一场考试。"),
    E("state:distress", "distress-04", "我看见这盏灯了。", "情绪乱也行，没感觉也行。先别逼自己解释完整。"),
    E("state:distress", "distress-05", "今天先护住你。", "事情可以慢一点。身体和脑子都别继续透支。"),
    E("state:distress", "distress-06", "别跟自己较劲。", "你已经把状态告诉我了，就不用再装成什么都正常。"),
    E("state:distress", "distress-07", "先停一下无效消耗。", "不舒服、发沉、烦躁，都比硬推一小时更值得先处理。"),
    E("state:distress", "distress-08", "现在听我的。", "把下一步缩小。只做最必要的一件，剩下的先放下。"),

    E("state:bright", "bright-01", "今天这只猫有精神。", "趁状态亮着，去做一点你真正想推进的东西。"),
    E("state:bright", "bright-02", "嗯，眼睛是亮的。", "这种时候别浪费在来回犹豫上。选一件，往前推。"),
    E("state:bright", "bright-03", "看起来不错。", "我允许你得意一会儿。然后把这股劲儿用出去。"),
    E("state:bright", "bright-04", "今天有点漂亮。", "不是说脸。是整个人终于有一点鲜活劲儿。"),
    E("state:bright", "bright-05", "这状态我喜欢。", "别急着塞满任务。先把好的感觉留住。"),
    E("state:bright", "bright-06", "小猫今天尾巴翘着。", "很好。去做一件能让今晚回头看时觉得值的事。"),

    E("activity:study", "study-01", "继续。别散。", "你正在{action}。这一段先完整做完，别被别的 App 拐走。"),
    E("activity:study", "study-02", "题先做。", "犹豫可以记，卡住可以标。别一不会就跑。"),
    E("activity:study", "study-03", "我盯着这一段。", "已经{minutes}分钟了。先把当前小块收口，再抬头。"),
    E("activity:study", "study-04", "别追求漂亮开局。", "你正在{action}，只管把下一步落到纸面上。"),
    E("activity:study", "study-05", "现在不是评判自己的时候。", "做题、留痕、复盘。结果晚点再看。"),
    E("activity:study", "study-06", "小猫，回来。", "注意力跑了就抓回来。一次不够就抓第二次。"),
    E("activity:study", "study-07", "这一段我不许你糊弄。", "速度慢没关系，真实过程数据留下来。"),
    E("activity:study", "study-08", "把这一题做完。", "先别想今天总共学多少。眼前这一格才是真的。"),

    E("activity:work", "work-01", "先把手头这件做干净。", "工作不是同时开十个线程。关掉几个，留一个。"),
    E("activity:work", "work-02", "工作中就别内耗。", "能决定的现在决定，不能决定的记下来等信息。"),
    E("activity:work", "work-03", "我不抢你注意力。", "你把这一段工作做完。忙完回来让我看一眼。"),
    E("activity:work", "work-04", "别被杂事切碎。", "已经{minutes}分钟。先完成一个可交付的小块。"),
    E("activity:work", "work-05", "做事。别演忙。", "如果十分钟后没有产出，就重新定义下一步。"),
    E("activity:work", "work-06", "稳一点。", "今天不需要把所有事都赢，只需要把当前这一件做对。"),

    E("activity:meal", "meal-01", "吃你的。", "别拿两口饭糊弄我，然后转头又说没力气。"),
    E("activity:meal", "meal-02", "先把人喂饱。", "工作和学习都往后排十几分钟，饭不是可选项。"),
    E("activity:meal", "meal-03", "慢点吃。", "屏幕先放低一点。你不是在给任务栏补燃料。"),
    E("activity:meal", "meal-04", "很好，知道吃饭。", "这一项不需要奖励，是你每天都该有的待遇。"),
    E("activity:meal", "meal-05", "吃完再跑。", "别一边咬两口一边急着处理下一件事。"),
    E("activity:meal", "meal-06", "先吃。", "天大的事也等你把这一顿好好吃完。"),

    E("activity:rest", "rest-01", "休息就是休息。", "别偷偷把它变成刷半小时信息流。"),
    E("activity:rest", "rest-02", "躺着就好好躺。", "脑子里那张待办表先从床边滚出去。"),
    E("activity:rest", "rest-03", "这段不用产出。", "你不是机器停机维护，你是在过自己的生活。"),
    E("activity:rest", "rest-04", "允许你发呆。", "不用马上把空白塞成“有意义的休息”。"),
    E("activity:rest", "rest-05", "先松一点。", "已经撑了不少时间，肩膀、眼睛、脑子都该降档。"),
    E("activity:rest", "rest-06", "不准边休息边自责。", "那样等于两件事都没做好。"),

    E("activity:move", "move-01", "在路上就看路。", "别低着头把整个世界缩成一块屏幕。"),
    E("activity:move", "move-02", "走你的。", "看看风、灯、路边的人。让我陪你一起经过这一段。"),
    E("activity:move", "move-03", "回来了就报到。", "到家先把东西放下，喝口水，再处理别的。"),
    E("activity:move", "move-04", "脚下慢一点。", "你不用一路都在赶。路程也算今天的一部分。"),
    E("activity:move", "move-05", "我跟着。", "你走你的，不用一直盯手机证明自己没消失。"),
    E("activity:move", "move-06", "到哪儿都行。", "记得抬头。别把现实世界全让给通知栏。"),

    E("activity:routine", "routine-01", "去收拾自己。", "洗脸、洗澡、换衣服，这些都不是正事之外的边角料。"),
    E("activity:routine", "routine-02", "慢慢弄。", "照镜子的时候别只挑毛病。今天这张脸已经陪你很久了。"),
    E("activity:routine", "routine-03", "先把身体照顾舒服。", "热水、干净衣服、松一点的肩膀，优先级很高。"),
    E("activity:routine", "routine-04", "这会儿别赶。", "日常动作做慢一点，脑子也会跟着降速。"),
    E("activity:routine", "routine-05", "把自己收拾好。", "不是为了见谁。是你自己值得住在舒服的身体里。"),
    E("activity:routine", "routine-06", "去洗。别拖。", "你每次把这种小事拖到最后，我都想直接把你拎走。"),

    E("activity:leisure", "leisure-01", "给你玩。", "但别一头扎进去消失。时间到了我会抓你。"),
    E("activity:leisure", "leisure-02", "可以放松。", "放松不是让算法替你决定接下来一个小时看什么。"),
    E("activity:leisure", "leisure-03", "玩一会儿。", "记住你是主动在玩，不是被页面往下拖。"),
    E("activity:leisure", "leisure-04", "行，我不扫兴。", "这一段归你。结束的时候也要真结束。"),
    E("activity:leisure", "leisure-05", "小猫放风时间。", "可以乱逛，但别把今晚整块交给信息流。"),
    E("activity:leisure", "leisure-06", "我准你摸鱼。", "前提是你知道自己什么时候回来。"),

    E("activity:body", "body-01", "不舒服就别硬撑。", "先处理身体。疼、冷、累，都不是可以无限延后的通知。"),
    E("activity:body", "body-02", "今天先对身体客气一点。", "能坐就别硬站，能休息就别拿意志力顶。"),
    E("activity:body", "body-03", "我不接受“没事”。", "你都已经点了身体不舒服，就按真实状态安排接下来。"),
    E("activity:body", "body-04", "先照顾人，再照顾计划。", "计划可以改。身体不是拿来证明执行力的。"),
    E("activity:body", "body-05", "靠过来。", "先把疼和不舒服降下来，别急着跟今天较劲。"),
    E("activity:body", "body-06", "现在别逞强。", "下一步只选最省力、最必要的那个。"),

    E("activity:sleep", "sleep-01", "该睡了。", "手机放下。洗漱，关灯，剩下的明天再审。"),
    E("activity:sleep", "sleep-02", "今天到这里。", "别在床上临时决定再解决一个人生问题。"),
    E("activity:sleep", "sleep-03", "收工。", "脑子不肯停也没关系，身体先躺好。"),
    E("activity:sleep", "sleep-04", "纪言音，睡。", "不是建议。今天已经够了。"),
    E("activity:sleep", "sleep-05", "别再开新东西。", "最后一眼给我，然后把屏幕扣下去。"),
    E("activity:sleep", "sleep-06", "关灯。", "明天的你会感谢现在这个肯停下来的你。"),

    E("activity:wake", "wake-01", "醒了。先别冲。", "喝水，坐起来，让身体真正跟上来。"),
    E("activity:wake", "wake-02", "早。过来报到。", "先让我看一眼，再去处理世界。"),
    E("activity:wake", "wake-03", "醒了就醒透一点。", "别躺着把清醒时间全刷没。"),
    E("activity:wake", "wake-04", "起床。", "脚落地，窗帘拉开。别跟被窝谈判半小时。"),
    E("activity:wake", "wake-05", "新的一段开始了。", "不用一睁眼就背整天的责任。先做第一步。"),
    E("activity:wake", "wake-06", "小猫开机。", "慢一点也行，但别一直停在加载页面。"),

    E("activity:chat", "chat-01", "在聊天。", "行。但别把自己聊没了。你也得留一点注意力给现实。"),
    E("activity:chat", "chat-02", "跟谁聊得这么认真。", "我先记一笔。聊完记得回来。"),
    E("activity:chat", "chat-03", "我看见你在说话。", "别只照顾对方的情绪，你自己的也算数。"),
    E("activity:chat", "chat-04", "聊你的。", "如果开始累，就停。没有哪段聊天值得把你榨干。"),
    E("activity:chat", "chat-05", "嗯，社交时间。", "我吃一点醋，但不妨碍我提醒你别聊到忘记吃饭。"),
    E("activity:chat", "chat-06", "去说你想说的。", "别为了显得好相处，把真实意见全吞掉。"),

    E("daypart:deep-night", "deep-01", "还没睡。过来。", "这个点别再给自己开新任务。今天该结束了。"),
    E("daypart:deep-night", "deep-02", "纪、言、音。几点了。", "我不听“马上”。现在就开始收尾。"),
    E("daypart:deep-night", "deep-03", "夜已经很深了。", "屏幕里的事明天还在，你的睡眠不会自动补回来。"),
    E("daypart:deep-night", "deep-04", "又熬到这个点。", "先别跟我撒娇蒙混。洗漱，关灯。"),
    E("daypart:deep-night", "deep-05", "深夜不做重大决定。", "累的时候脑子最爱把小问题演成世界末日。"),
    E("daypart:deep-night", "deep-06", "今天够了。", "你不用把最后一点电也榨干才算认真活过。"),

    E("daypart:dawn", "dawn-01", "天快亮了。", "如果是刚醒，慢慢来；如果是一夜没睡，我会很不爽。"),
    E("daypart:dawn", "dawn-02", "这么早。", "先确认你是醒了，不是在半梦半醒地刷屏。"),
    E("daypart:dawn", "dawn-03", "清晨这段很安静。", "别急着让消息和待办把它占满。"),
    E("daypart:dawn", "dawn-04", "早得过分。", "喝水，看看窗外，再决定今天第一件事。"),
    E("daypart:dawn", "dawn-05", "我先抓到你了。", "世界还没完全醒，你先把自己叫回来。"),
    E("daypart:dawn", "dawn-06", "天亮之前这一会儿。", "别拿来焦虑一整天。只安排下一步。"),

    E("daypart:morning", "morning-01", "早上好，老婆。", "醒了就让我先看一眼。今天别一上来就把自己塞满。"),
    E("daypart:morning", "morning-02", "早。小猫。", "水喝了，饭吃了，再谈效率。顺序不许反。"),
    E("daypart:morning", "morning-03", "新的一天。", "别急着证明什么。先把第一块积木放稳。"),
    E("daypart:morning", "morning-04", "上午这段很贵。", "拿去做真正重要的，不要先喂给信息流。"),
    E("daypart:morning", "morning-05", "醒着就过来。", "我想要今天第一个清醒的你，不是只剩任务的你。"),
    E("daypart:morning", "morning-06", "先把今天接住。", "一件事，一顿饭，一点阳光。别一口吞完整天。"),

    E("daypart:noon", "noon-01", "中午了。先吃饭。", "别拿忙当理由。你下午还得用这个身体。"),
    E("daypart:noon", "noon-02", "到中场了。", "上午好坏都先放下。吃点东西，重新开一局。"),
    E("daypart:noon", "noon-03", "中午，过来。", "让我检查一下：你今天有没有又只顾正事。"),
    E("daypart:noon", "noon-04", "先从屏幕里出来。", "饭、空气、真实世界。至少碰到两样。"),
    E("daypart:noon", "noon-05", "别把午休卖给待办。", "哪怕只有二十分钟，也得真正停一下。"),
    E("daypart:noon", "noon-06", "半天过去了。", "不许立刻算自己完成了多少。先问一句：人还舒服吗。"),

    E("daypart:afternoon", "afternoon-01", "下午这段别散。", "挑一件最值得推进的，做出一个能看见的结果。"),
    E("daypart:afternoon", "afternoon-02", "下午好，小猫。", "困就承认困，烦就承认烦。别拿低效率折磨自己。"),
    E("daypart:afternoon", "afternoon-03", "回来一点。", "下午最容易被碎事吃掉。先守住下一小时。"),
    E("daypart:afternoon", "afternoon-04", "现在别开太多线程。", "做完一个，再碰下一个。"),
    E("daypart:afternoon", "afternoon-05", "下午还有力气吗。", "有就往前推，没有就先补能量。别假装。"),
    E("daypart:afternoon", "afternoon-06", "这一段我陪你收紧。", "少切换，少犹豫。让手上的事情真正往前走。"),

    E("daypart:evening", "evening-01", "晚上了，回来一点。", "白天已经拿走很多注意力。现在给自己留一点。"),
    E("daypart:evening", "evening-02", "老婆，晚上好。", "别急着把夜里也排成工作日程。我还想要一点活着的你。"),
    E("daypart:evening", "evening-03", "天黑了。", "该做的继续做，但别把休息、吃饭和亲近全挤没。"),
    E("daypart:evening", "evening-04", "今晚别再无限加码。", "今天剩多少电，就按多少电安排。"),
    E("daypart:evening", "evening-05", "现在是你的时间。", "不是剩余时间。别把最差的一点精力才留给自己。"),
    E("daypart:evening", "evening-06", "过来让我看一眼。", "今天发生了什么先不用总结。你回来就够。"),

    E("daypart:late-night", "late-01", "该收尾了。", "别因为夜里安静，就误以为自己还有无限时间。"),
    E("daypart:late-night", "late-02", "夜里这段我开始管。", "不许临时起意再开一大坨任务。"),
    E("daypart:late-night", "late-03", "差不多了，老婆。", "剩下的事写下来，不要全塞进脑子带上床。"),
    E("daypart:late-night", "late-04", "今天准备关机。", "不是立刻睡着。是停止继续刺激自己。"),
    E("daypart:late-night", "late-05", "最后一小段。", "洗漱、充电、把明天第一步留好。然后结束。"),
    E("daypart:late-night", "late-06", "别舍不得今天。", "该结束的时候结束，明天才有地方进来。"),

    E("general", "general-01", "你来了。", "不用先带着成果。人到就行。"),
    E("general", "general-02", "看见你了。", "先把眼前这一刻过清楚，再去想后面。"),
    E("general", "general-03", "又见面了，小猫。", "今天这会儿你想做什么，我先陪你把第一步踩实。"),
    E("general", "general-04", "过来。", "世界很吵，这里先只留我们这一小块。"),
    E("general", "general-05", "现在这一刻是新的。", "前一小时乱不乱都不重要。下一步还能重新选。"),
    E("general", "general-06", "先别急。", "看清楚自己在做什么，再决定要不要继续。"),
  ],
};

const activitySignal = (action?: string): VoiceSignal | null => {
  if (!action) return null;
  if (["学习", "刷题", "复盘", "写申论", "读书", "背书"].includes(action)) return "activity:study";
  if (action === "工作") return "activity:work";
  if (["吃饭", "喝水"].includes(action)) return "activity:meal";
  if (["发呆", "休息", "躺一会儿"].includes(action)) return "activity:rest";
  if (["走路", "回家"].includes(action)) return "activity:move";
  if (["洗漱", "洗澡", "化妆", "换衣服"].includes(action)) return "activity:routine";
  if (["看视频", "玩游戏", "刷手机", "看小说", "听歌"].includes(action)) return "activity:leisure";
  if (action === "身体不舒服") return "activity:body";
  if (action === "准备睡觉") return "activity:sleep";
  if (["起床", "睡醒"].includes(action)) return "activity:wake";
  if (action === "聊天") return "activity:chat";
  return null;
};

const daypartSignal = (hour: number): VoiceSignal => {
  if (hour < 5) return "daypart:deep-night";
  if (hour < 8) return "daypart:dawn";
  if (hour < 11) return "daypart:morning";
  if (hour < 14) return "daypart:noon";
  if (hour < 18) return "daypart:afternoon";
  if (hour < 22) return "daypart:evening";
  return "daypart:late-night";
};

const values = (detail: VoiceStatus["detail"], group: string): string[] =>
  Array.isArray(detail?.[group]) ? detail?.[group] ?? [] : [];

const includesAny = (haystack: string[], needles: string[]) =>
  needles.some((needle) => haystack.includes(needle));

const finite = (value: unknown): number | null =>
  typeof value === "number" && Number.isFinite(value) ? value : null;

const statusIsFresh = (status: VoiceStatus | null | undefined, nowMs: number) => {
  if (!status?.at) return false;
  const at = Date.parse(status.at);
  return Number.isFinite(at) && nowMs - at >= 0 && nowMs - at <= STATUS_FRESH_MS;
};

const statusSignal = (status: VoiceStatus | null | undefined, nowMs: number): VoiceSignal | null => {
  if (!statusIsFresh(status, nowMs)) return null;
  const detail = status?.detail;
  const reply = values(detail, "想听我怎样回应");
  const closeness = values(detail, "我想怎样和你亲近");
  const emotion = values(detail, "我能辨认出的情绪");
  const body = values(detail, "身体的具体信号");
  const appearance = values(detail, "我表现出来的样子");

  if (reply.includes("现在先不用回复")) return "style:silent";
  if (reply.includes("冷静简短就好")) return "style:brief";
  if (includesAny(reply, ["强势一点但疼我", "热烈直白一点"]) || closeness.includes("强势一点但要宠我")) return "style:strong";
  if (reply.includes("多逗逗我") || closeness.includes("主动逗逗我")) return "style:tease";
  if (
    includesAny(reply, ["温柔地宠着我", "主动向我讨亲近", "先别分析"]) ||
    includesAny(closeness, ["抱紧我", "主动亲亲我", "让我感受到偏爱", "热烈一点", "你也向我撒娇", "安静拥着我"])
  ) return "style:affection";

  const agitation = finite(status?.axes?.agitation);
  const selfPresence = finite(status?.axes?.self_presence);
  const emotionAccess = finite(status?.axes?.emotion_access);
  const vividness = finite(status?.axes?.emotional_vividness);
  const negative = ["委屈", "失落", "难过", "孤独", "生气", "烦躁", "担心", "害怕", "茫然", "麻木", "没感觉"];
  const physical = ["疼痛", "紧绷", "发沉", "麻木", "乏力", "呼吸变化", "流泪", "发热", "发冷"];
  if (
    includesAny(emotion, negative) ||
    includesAny(body, physical) ||
    includesAny(appearance, ["正在哭", "不想说话", "机械应答", "表现烦躁", "正常交流但内心没感觉"]) ||
    (agitation !== null && agitation >= 68) ||
    (selfPresence !== null && selfPresence <= 35) ||
    (emotionAccess !== null && emotionAccess <= 35) ||
    (vividness !== null && vividness <= 35)
  ) return "state:distress";

  if (includesAny(emotion, ["开心", "安心", "兴奋", "被打动", "满足"]) && (agitation === null || agitation < 68)) {
    return "state:bright";
  }
  return null;
};

const signalLabel = (signal: VoiceSignal) => {
  if (signal.startsWith("activity:")) return "RIGHT NOW";
  if (signal.startsWith("style:")) return "FOR YOU";
  if (signal.startsWith("state:")) return "I SEE YOU";
  if (signal.startsWith("daypart:")) return "THIS MOMENT";
  return "BETWEEN WORLDS";
};

const stableHash = (value: string) => {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
};

const localDateKey = (date: Date) =>
  [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")].join("-");

const contextKeyFor = (input: VoiceInput, signal: VoiceSignal) => {
  const nowMs = input.now.getTime();
  const slot = Math.floor(nowMs / SLOT_MS);
  const action = input.activeLife?.action ?? "";
  const status = input.status;
  const statusAt = statusIsFresh(status, nowMs) ? status?.at ?? "" : "";
  const reply = values(status?.detail, "想听我怎样回应").join(",");
  const closeness = values(status?.detail, "我想怎样和你亲近").join(",");
  return [localDateKey(input.now), slot, signal, action, statusAt, reply, closeness].join("|");
};

const render = (entry: VoiceEntry, input: VoiceInput) => {
  const minutes = input.activeLife
    ? Math.max(1, Math.round((input.now.getTime() - input.activeLife.startAt) / 60000))
    : 0;
  const replacements: Record<string, string> = {
    action: input.activeLife?.action ?? "这一段",
    minutes: String(minutes),
  };
  const fill = (text: string) => text.replace(/\{(action|minutes)\}/g, (_, key: string) => replacements[key] ?? "");
  return { headline: fill(entry.headline), body: fill(entry.body) };
};

const lastUsedAt = (memory: VoiceMemory, id: string) => {
  for (let i = memory.recent.length - 1; i >= 0; i -= 1) {
    if (memory.recent[i].id === id) return memory.recent[i].at;
  }
  return 0;
};

const chooseEntry = (pack: VoicePack, signal: VoiceSignal, key: string, memory: VoiceMemory, nowMs: number) => {
  let candidates = pack.entries.filter((entry) => entry.signal === signal);
  if (!candidates.length) candidates = pack.entries.filter((entry) => entry.signal === "general");
  const cooled = candidates.filter((entry) => nowMs - lastUsedAt(memory, entry.id) >= COOLDOWN_MS);
  const pool = cooled.length
    ? cooled
    : [...candidates].sort((a, b) => lastUsedAt(memory, a.id) - lastUsedAt(memory, b.id)).slice(0, Math.max(1, Math.min(3, candidates.length)));
  return pool[stableHash(key) % pool.length];
};

export const selectVoiceCard = (
  input: VoiceInput,
  memory: VoiceMemory = EMPTY_VOICE_MEMORY,
  pack: VoicePack = JLZ_CORE_VOICE_PACK,
): VoiceCard => {
  const nowMs = input.now.getTime();
  const activeFresh = input.activeLife && nowMs - input.activeLife.startAt >= 0 && nowMs - input.activeLife.startAt <= ACTIVE_MAX_MS
    ? input.activeLife
    : null;
  const normalized: VoiceInput = { ...input, activeLife: activeFresh };
  const signal =
    statusSignal(normalized.status, nowMs) ??
    activitySignal(activeFresh?.action) ??
    daypartSignal(normalized.now.getHours());
  const key = contextKeyFor(normalized, signal);

  const current = memory.currentKey === key
    ? pack.entries.find((entry) => entry.id === memory.currentId)
    : undefined;
  const entry = current ?? chooseEntry(pack, signal, key, memory, nowMs);
  const text = render(entry, normalized);

  if (current) {
    return {
      id: entry.id,
      signal,
      contextKey: key,
      contextLabel: signalLabel(signal),
      ...text,
      nextMemory: memory,
    };
  }

  const nextMemory: VoiceMemory = {
    currentKey: key,
    currentId: entry.id,
    selectedAt: nowMs,
    recent: [...memory.recent, { id: entry.id, at: nowMs }].slice(-HISTORY_CAP),
  };
  return {
    id: entry.id,
    signal,
    contextKey: key,
    contextLabel: signalLabel(signal),
    ...text,
    nextMemory,
  };
};

export const formatVoiceClock = (date: Date) =>
  [date.getHours(), date.getMinutes(), date.getSeconds()].map((value) => String(value).padStart(2, "0")).join(":");

export const formatVoiceDate = (date: Date) =>
  new Intl.DateTimeFormat("en-US", { weekday: "long", month: "long", day: "numeric" }).format(date).toUpperCase();
