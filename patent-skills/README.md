# Patent skills / 专利技能

PatentKing 的领域能力一部分来自公开的第三方技能仓库。这些仓库自带专利全文、评测集和嵌套 Git 历史，属于数据依赖，不放进本仓库。

Part of PatentKing's domain behavior comes from public third-party skill repositories. Those checkouts include patent full texts, evaluation sets, and nested Git history, so they are treated as local data and are not published here.

克隆到 `patent-skills/` 下对应目录后，可用 `scripts/pack-skills.sh` 打包，再用 `scripts/pk-import-skills.sh` 导入本地平台。

Clone each repo into the matching directory under `patent-skills/`, then pack with `scripts/pack-skills.sh` and import with `scripts/pk-import-skills.sh`.

| 目录 Directory | 上游 Upstream |
|----------------|---------------|
| `PatentRadar/` | https://github.com/yuc16/PatentRadar |
| `fuyuxiang-patent-disclosure-skill/` | https://github.com/fuyuxiang/patent-disclosure-skill |
| `handsomestWei-patent-disclosure-skill/` | https://github.com/handsomestWei/patent-disclosure-skill |
| `paper2patent/` | https://github.com/7toCR/paper2patent |
| `patent-mining-disclosure-skill/` | https://github.com/AqooDer/patent-mining-disclosure-skill |

`tokenlab-imagegen/` 是本仓库自带的附图生成技能，只读取环境变量 `TOKENLAB_API_KEY`，不保存密钥。

`tokenlab-imagegen/` ships with this repository. It reads `TOKENLAB_API_KEY` from the environment and does not store the key.
