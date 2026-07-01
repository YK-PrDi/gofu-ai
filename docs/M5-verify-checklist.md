# M5 真实 LLM 效果验证清单

> 在**有 API 密钥的环境**（你本地或云端服务器）照此验证 M5 双轨的真实 LLM 输出。
> 无密钥环境只能验链路通、验不了输出质量——这份清单是给有密钥环境用的。

## 前置：配密钥启动云端

```bash
# 需要的环境变量（按实际填）
export GEMINI_API_KEY=xxx           # 或 GPT_IMAGE_KEY_1..4（生图）
export DASHSCOPE_API_KEY=xxx        # 万相/通义
export COS_SECRET_ID=xxx COS_SECRET_KEY=xxx   # COS（可选，无则图存本地路径）
# LY 文本线（卖点/规划/标题）密钥，走 ly-image.text.api-key：
export LY_TEXT_API_KEY=xxx          # 对应 application.yml 里 ly-image.text.api-key

cd gofu-ai && mvn -N install && mvn -pl gofu-shared install -DskipTests
cd gofu-server-cloud && mvn spring-boot:run   # 5020
```

> ⚠️ 确认 `application.yml` 里 `ly-image.text.api-key` 已配（LyTextService→AiImageClient.geminiText 用它，
> 对应 LyImageProperties.text.apiKey）。若日志报"文本生成密钥未配置（ly-image.text.api-key）"，就是这个键没配。

## 验证步骤（依次跑，中文用文件传避免 shell 编码问题）

### 1. 建商品上下文
```bash
cat > ctx.json <<'JSON'
{"productId":"GF-106","category":"家装主材>卫浴>花洒喷头","mainItem":"猫爪增压花洒",
 "visual":{"title":"GOFU猫爪增压过滤花洒 免安装大出水"},"status":"DRAFT"}
JSON
curl -s -X POST http://localhost:5020/api/context -H "Content-Type: application/json; charset=utf-8" --data-binary @ctx.json
# 记下返回的 id → 设为 CTXID
```

### 2. 卖点提取（验证：能否从标题提出合理卖点）
```bash
cat > sp.json <<JSON
{"contextId":"$CTXID","title":"GOFU猫爪增压过滤花洒 免安装大出水","productType":"花洒"}
JSON
curl -s -X POST http://localhost:5020/api/gen/selling-points -H "Content-Type: application/json; charset=utf-8" --data-binary @sp.json
```
**✅ 预期**：返回 `{"sellingPoints":["增压","过滤","免安装","大出水"...]}` 这类精炼关键词。
**❌ 若空数组**：密钥没配对，看日志"卖点提取失败"。

### 3. SKU 规划（验证核心：是否围绕卖点策划型号名）
```bash
cat > plan.json <<JSON
{"contextId":"$CTXID","category":"家装主材>卫浴>花洒喷头","productName":"猫爪花洒","brand":"GOFU","material":"塑料",
 "skus":[{"itemCode":"GF-106-银色","name":"银色花洒","role":"main"},
         {"itemCode":"001滤芯","name":"过滤滤芯","role":"batch"},
         {"itemCode":"银底座","name":"底座","role":"accessory"}]}
JSON
curl -s -X POST http://localhost:5020/api/gen/sku-plans -H "Content-Type: application/json; charset=utf-8" --data-binary @plan.json
```
**✅ 预期核心**：返回的 plans 里，型号 specName 应**体现步骤2提取的卖点**（如"增压过滤单喷头""过滤喷头+5支滤芯"）。
**关键判断**：对比"传卖点" vs "不传卖点"两次结果——传了卖点的型号名应更紧扣"增压/过滤"。可临时清空 context 卖点再跑一次对照。

### 4. 标题生成（验证三种模式）
```bash
cat > title.json <<JSON
{"contextId":"$CTXID","mode":"titlelib","category":"家装主材>卫浴>花洒喷头","brand":"GOFU","skuNames":["银色","黑色"]}
JSON
curl -s -X POST http://localhost:5020/api/gen/title -H "Content-Type: application/json; charset=utf-8" --data-binary @title.json
```
**✅ 预期**：返回 `{"title":"GOFU...","skuNames":{"银色":"...","黑色":"..."}}`，标题≤30字、品牌开头、含卖点词。

### 5. 生图（验证真出图 + COS + 写回 context）
```bash
# 需真实白底图路径。templateId=sticker-leftcard 走花洒贴图模式
cat > gen.json <<JSON
{"contextId":"$CTXID","productType":"花洒","refImagePath":"/真实/主图.jpg","templateId":"sticker-leftcard",
 "skus":[{"idx":0,"name":"银色","compDesc":"增压过滤+5支滤芯","itemCode":"GF-106-银色+001滤芯*5",
          "whiteImgPath":"/真实/白底.jpg","accParts":[{"code":"001滤芯","qty":5,"kw":"滤芯"}]}]}
JSON
curl -s -X POST http://localhost:5020/api/ly-gen/sku-images -H "Content-Type: application/json; charset=utf-8" --data-binary @gen.json
```
**✅ 预期**：返回 `{"images":[{"name":"银色","path":"...","cosKey":"generated/日期/xx.jpg"}]}`。
检查生成图：左侧配件卡文案对不对（银底座/n米软管/过滤滤芯*N）、字体是否正常（重点，验字体探测生效）。

### 6. 看两轨数据是否齐全（数据孤岛打通的最终证明）
```bash
curl -s "http://localhost:5020/api/context/$CTXID"
```
**✅ 预期**：一个 context 里同时有 `visual.sellingPoints`（卖点）、`visual.title`（标题）、`visual.mainImages`（图key）、`structure.plans`（方案）——四样都齐 = 双轨在同一上下文串通。

### 7. COS 换签（验证永久key→签名URL）
```bash
curl -s "http://localhost:5020/api/gen/sign?key=generated/日期/xx.jpg"
```
**✅ 预期**：返回带 `?q-signature=` 的 7 天签名 URL。

## 验证要点总结

| 验什么 | 看什么 | 通过标准 |
|---|---|---|
| 卖点提取 | 步骤2 | 提出合理精炼关键词（非空非整句） |
| **卖点反哺**（核心） | 步骤3 | 型号名紧扣卖点，对照组明显差异 |
| 标题质量 | 步骤4 | ≤30字/品牌开头/含卖点 |
| 生图+字体 | 步骤5 | 出图正常，左侧文案对、中文字体不乱 |
| 数据孤岛打通 | 步骤6 | 一个context四样产物齐全 |
| COS永久key | 步骤5/7 | 存key、能换签名URL |

发现问题按现象反馈，我对着代码定位。**尤其步骤3的"卖点反哺"效果**——这是 GOFU-AI 的核心价值，值得重点看。
