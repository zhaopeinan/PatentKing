# Tokenlab gpt-image-2 API reference

Use `POST {BASE_URL}/images/generations` with JSON:

```json
{
  "model": "gpt-image-2",
  "prompt": "...",
  "n": 1,
  "size": "1024x1024",
  "quality": "high",
  "output_format": "png"
}
```

Headers:

```http
Authorization: Bearer $TOKENLAB_API_KEY
Content-Type: application/json
```

Decode the returned `data[0].b64_json` field and save it as the requested file.
