# Jev 原理调查与独立决策模型实验方案

核查日期：2026-09-19。目标：明确可实现的技术路线，不将社区实现称为 TypeSafe Jev 的内部实现。本文为研究与实验设计；未下载模型、租用 GPU、运行训练、发送付费 API 请求或修改 ROM 下载任务。

## 结论与证据层级

可以复现“状态与问题输入、有限候选决策与概率输出”的机制；可以微调拥有自有适配权重的领域决策模型。不能根据当前公开材料复现 Jev 的完整训练配方或保证同等通用能力。

- **官方已披露**：Jev 面向结构化决策；提出新架构、并行 sampler、Reinforcement Learning for Calibrated Decisions（RLCD）。公开资料解释训练目标，但本次未找到足以复现的层级架构、权重、完整训练集或 RLCD 训练代码。[发布说明](https://typesafe.ai/blog/introducing-system-one-models-and-jev)、[训练目标说明](https://docs.typesafe.ai/introduction/machine-learning-primer)。
- **官方 API**：state + questions → Choice/Score/Noul；这是接口契约，不是训练实现。[快速开始](https://docs.typesafe.ai/introduction/quickstart)。
- **官方开源对照**：[system-one-adapter-python](https://github.com/typesafe-ai/system-one-adapter-python) 用其他 LLM API 实现同形接口，是基准适配器，不包含 Jev 权重。
- **官方承认的边界**：数值、日期、多层推理、无关长文本及对抗内容可导致错误；Choice 与 Noul 的概率并不保证满足跨问题的一致性。[jev-1.13 已知问题](https://docs.typesafe.ai/model-jaggedness/jev-1.13)。
- **不要混淆缩写**：2023 年论文 [RLCD: Reinforcement Learning from Contrastive Distillation](https://arxiv.org/abs/2307.12950) 与 Jev 的 Calibrated Decisions 不是同一个方法。

检索覆盖官方文档索引、发布说明、训练目标说明、已知问题、官方适配器，以及下列社区作者自己发布的模型卡和代码。GitHub API 目录请求受限，改用可访问的原始文件和 Hugging Face 文件目录；“未找到”不代表能证明任何地方都没有公开。

## 找到的两种可操作实现

### 1. Qwen 并行候选打分：最快做机制基线

项目：[harshatheg/Qwen-2.5-1B-RLCD](https://huggingface.co/harshatheg/Qwen-2.5-1B-RLCD)。检查 [文件目录](https://huggingface.co/harshatheg/Qwen-2.5-1B-RLCD/tree/main) 和 [PyTorch 实现](https://huggingface.co/harshatheg/Qwen-2.5-1B-RLCD/blob/main/core/engine_torch.py) 后发现：该目录当前主要发布推理代码；PyTorch 默认加载 `Qwen/Qwen2.5-1.5B-Instruct`。不能因为名称含 RLCD/1B 就称其为经过 RLCD 训练的新 1B 权重。

代码路线是共享前缀 prefill/KV cache、批量问题后缀、抽取候选 token logits、softmax、程序组装结构化结果。温度参数存在，但看到 softmax 或 temperature 并不能证明概率校准成功。模型卡的速度是作者报告，未在本机复测。

### 2. Laya：有公开权重、决策头和训练示例

项目：[GitHub](https://github.com/NandhaKishorM/laya)、[模型卡及权重](https://huggingface.co/convaiinnovations/laya)。作者报告 ModernBERT-large 骨干，加两层 Transformer 决策头与候选 marker scorer，总计约 421M 参数，英文、每题 512 tokens。目录确有约 843 MB 的 model.safetensors。

实际检查 [rl_common.py](https://huggingface.co/convaiinnovations/laya/blob/main/rl_common.py)：每个候选有 marker；获取其隐藏状态，经 scorer 得到 logit，屏蔽填充候选，softmax 后返回概率；另有 act/escalate head 和按问题类型保存的温度。它是具体可学习的源码参考。

[微调 notebook](https://github.com/NandhaKishorM/laya/blob/main/notebooks/laya_finetune_colab.ipynb) 展示给 logits 加高斯噪声、比较分布的 scoring reward、用组内基线计算 policy-gradient 的做法。这是社区自定义 RLCD，不是官方 Jev 算法。示例只有六条样本，不是完整训练复现；不要把跑通 notebook 当成获得可靠通用模型。模型卡比较不同数据集的准确率、以及本地 GPU 与远端 API 延迟，不能由此推出超越 Jev。

中文手机状态应单独评测；其模型卡的英文范围不能外推为适合 DroidUse。

## 我们建议实现的机制

先以支持中文的 [Qwen2.5-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) 作可复现的小模型基线。选择它是为了与已核查的开源实现对照，不是声称它是最强或最新模型。模型卡标注 Apache-2.0；发布前还需核对全部依赖与训练数据许可。

输入：state、question、candidate IDs 与描述。第一版候选为 WAIT、REOBSERVE、BACK、ASK_USER；另外加入 NONE/UNKNOWN 的语义与数据，避免被迫在全部错误的选项中选一个。

将候选映射成经 tokenizer 核验的不同单 token 标签（例如 A/B/C/D，但必须验证当前模板边界下的实际切分），一次前向获得最后位置 logits：

```python
# 机制示意，不是已经执行的代码；batch 先只放一个问题，避免 padding 索引错误。
with torch.inference_mode():
    out = model(**inputs)
scores = out.logits[0, -1, label_token_ids].float()
probabilities = torch.softmax(scores / temperature, dim=-1)
winner = candidates[int(probabilities.argmax())]
# 由程序输出 JSON，不调用 generate() 逐字生成 JSON。
```

严禁只取中文候选文字的第一个 token：不同候选可能共享开头，或者答案长度不同。第一版限制小候选集并使用唯一标签；动态大量候选以后采用 marker head 或完整候选序列打分并处理长度偏差。

从一个 forward 得到所有候选分数，不意味着整个请求零计算或恒定耗时。长输入 prefill 仍有成本；多问题批处理仍消耗显存；KV 前缀共享是另一步优化。初版先保证单题与朴素 batch 正确，再加缓存，不能先追求演示速度。

类型结果映射：Choice=argmax；二元判断=正类概率；Score=有序等级的概率加权平均（只表示等级预期，不代表精确数值计算）。这些可共享候选评分机制，实际训练需区分题型。多问题有依赖时按层执行，不假装全部独立。

## 校准与训练：先可验证，再尝试 RL

阶段 A 不训练，用于回答“去掉自由文本解码是否更快、正确率损失多少”。

阶段 B 训练真实领域适配权重：使用标签交叉熵 `L=-log p(y|x)`；适配候选评分路径或增加分类/marker head，而不是要求模型写推理过程。先冻结骨干训练 head，再比较 LoRA/QLoRA 或解冻部分层。它已经是可训练的决策模型，不必从零预训练。

阶段 C 在**独立校准集**拟合正温度 T：`p=softmax(logits/T)`，最小化 NLL。原始 softmax 是归一化分数，不自动代表实际正确率。校准不能修复所有判断错误或分布漂移。依据：[Temperature scaling 论文](https://proceedings.mlr.press/v70/guo17a.html)。

阶段 D 才作强化学习对照实验。可先以负 Brier score `R=-sum((p-one_hot(y))**2)` 或 log score 衡量概率报告；在有标签、损失可微时直接优化通常更简单，不能为了叫 RLCD 就加入 RL。若采用 Laya 的噪声探索/组内 advantage 方案，须与同数据、同骨干、同计算预算的监督学习对照。加 KL/裁剪等项会改变目标；有限数据、模型误设和优化误差意味着 proper scoring rule 也不能保证模型天然校准。

如果研究多步操作 RL，应另定义环境、观测、合法动作、任务完成和副作用代价，再考虑 PPO 等轨迹训练；它解决长期收益，与单步概率报告校准不是一件事。第一版不用真实手机高风险操作作探索环境。

## 可复现实验清单

1. **环境与版本**：独立 Python 环境；PyTorch/Transformers/可选 MLX、PEFT、datasets、safetensors。保存精确依赖、模型 revision、tokenizer、候选模板及随机种子；不运行来历不明的 remote code。不要在正在下载 ROM 的工作目录安装环境。
2. **固定测试任务**：先 300–500 条人工核对的中文/英文状态，覆盖正常流程、无候选、加载、重复动作、冲突、长文本、恶意页面指令。这个数量用于可行性筛查，不足以证明低风险失败率。
3. **基线对照**：规则路由；同一 Qwen 生成简短 JSON；同一 Qwen 候选 logits；可选原版 Jev API（获得访问权限后）。让生成基线也关闭冗长推理、保持等价任务，不能只对比长篇输出。Jev 不是必需依赖。
4. **首批训练数据**：预算 3,000–10,000 条质量可控标注作为起点，按学习曲线调整。训练/开发/校准/测试建议 70/10/10/10，按 App、任务会话、模板来源分组；同一轨迹相邻帧不可跨集合。额外保留至少一种未见 App 或任务族。
5. **数据标签**：多个合理动作时记录允许集合或人工分布，别强迫虚假唯一答案。保存 annotation 来源和分歧；弱教师标签与真人/环境验证标签分开。历史只包含决策时已知信息，不能把后续成功结果泄漏进输入。
6. **训练对照**：未训练 logits、监督微调、监督微调+温度校准、可选 RL 方案；至少 3 个随机种子。用结果选择是否扩大到 30,000–100,000 条，数量不是保证。
7. **性能测量**：输入长度固定比较 256/1024/4096 tokens，问题数 1/4/16；先预热，再分别测冷启动、热推理和端到端；GPU 计时同步；记录硬件、峰值内存、p50/p95、吞吐。本地算子耗时不可直接与公网 API 总延迟比较。
8. **正确性与不确定性**：accuracy/macro-F1、NLL、Brier、ECE与可靠性曲线；选项随机换序；拒绝率/覆盖率与剩余错误率同时报告。不能靠全部拒绝制造高正确率。按 App、语言、长度、候选数分别看数据。
9. **集成门槛（提议，非已达成绩）**：相同硬件短 JSON 对照下，热推理 p95 至少快 2 倍且准确率下降不超过 2 个百分点，才继续优化；在预先固定的拒绝阈值下评估覆盖率和置信区间，再决定自动化范围。支付、授权等边界仍由确定性代码处理。
10. **产物**：版本锁、模型/adapter 权重、tokenizer、T 与阈值、输入输出 schema、测试集哈希、延迟/准确率报告、失败样例和许可记录。量化后重新测校准和正确率，不能沿用未量化模型结论。

数据行草案（所有内容为合成示例）：

```json
{"episode_id":"demo-001","app_family":"demo-search","state":{"task":"查找书籍","page":"加载中","elapsed_ms":800,"previous_action":"tap_search","changed":false},"question":"下一步如何处理？","options":{"A":"在预算内等待","B":"重新观察","C":"返回","D":"请求人工"},"acceptable_labels":["A"],"source":"human_reviewed_synthetic"}
```

## 算力、时间与费用准备

本机只读检查：Apple Silicon arm64，32 GiB 统一内存；云构建机此前确认 16 vCPU/64 GB，无 GPU。

| 实验 | 建议资源（工程预算，未实测） | 说明 |
| --- | --- | --- |
| 1.5B 量化推理、候选 logits 对照 | 当前 32 GB Mac | 可先做，不需要购买新服务器；MLX 与 Torch 实现要分别验证 |
| CPU 验证接口/数据 | 当前云机 | 能做小规模功能检查，不用它的速度推断 GPU 性能；不抢 ROM 编译资源 |
| 约 400M 编码器领域微调 | 单卡 16–24 GB 显存起步 | 512-token 小 batch、checkpointing；是否够取决于优化器与激活 |
| 1.5B LoRA/QLoRA | 单卡 24 GB 作为较宽松首轮预算 | 从 512–1024 tokens、microbatch 1–4、梯度累积开始，先做显存探测 |
| 更长上下文/更大 batch/RL 多候选 | 48 GB 或更高，视 profiling 决定 | 不是第一步必须买的配置 |

[QLoRA 原论文](https://arxiv.org/abs/2305.14314) 说明量化骨干+低秩适配可减少微调内存，但不等于任意长度都能放入上述显存。1.54B 权重仅 BF16 理论约 3.08 GB；实际还包含激活、KV、优化器和框架开销，不能拿权重大小当总显存。

工作量初估：接口与无训练基线 1–3 个工作日；首批数据和首轮领域模型约 1–2 周；跨应用验证与部署需额外迭代。这是工程计划，不是保证。数据已有程度影响最大。

第一轮 GPU 可先限制 10–30 GPU 小时作预算上限，而非训练耗时预测。费用按供应商当时的每小时单价乘小时，加数据、存储和可选 API 成本；这里未查实时 GPU 报价，也未创建付费资源。先用免费本地基线筛掉没有价值的训练方向。

## 对 DroidUse 的接入边界

独立 decision client，返回闭集选项与概率；不要伪装成现有 ModelClient 的聊天 completion。先对 TaskLoop 状态做离线/影子评估；视觉模型继续负责页面理解，决策模型不直接得到任意 shell/Intent 权限。选择 targetId 后仍核对 session/frame/generation。不存在的输入隔离和原生保护不能由新模型补上，保持后端真实 ready 状态。

最终可交付的是有实测证据的 **DroidUse 领域决策模型**。通用 Jev 级别能力属于更大的数据与研究目标，不应在初版承诺。
