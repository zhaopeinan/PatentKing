<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import * as pkSettingsApi from '@/api/pkSettings'
import type { PkTokenlabSettingsDTO } from '@/types'

const loading = ref(false)
const saving = ref(false)

const form = ref<PkTokenlabSettingsDTO>({
  apiKey: '',
  baseUrl: 'https://api.tokenlab.cc.cd/v1',
  fallbackBaseUrl: 'https://hk.tokenlab.ccwu.cc/v1',
  model: 'gpt-image-2',
  size: 'auto',
  quality: 'high',
  network: 'direct',
  proxy: '',
})

const apiKeyConfigured = ref(false)

const sizeOptions = [
  { value: 'auto', label: '按图示自动判断（推荐）' },
  { value: '1536x1024', label: '1536x1024（横向架构）' },
  { value: '1024x1536', label: '1024x1536（竖向流程）' },
  { value: '2048x1152', label: '2048x1152（宽屏时序）' },
  { value: '1024x1024', label: '1024x1024（方形）' },
  { value: '2048x2048', label: '2048x2048' },
  { value: '3840x2160', label: '3840x2160' },
]

const qualityOptions = [
  { value: 'high', label: 'high（推荐）' },
  { value: 'medium', label: 'medium' },
  { value: 'low', label: 'low' },
  { value: 'auto', label: 'auto' },
]

const networkOptions = [
  { value: 'direct', label: '直连（推荐）' },
  { value: 'env', label: '使用系统代理' },
  { value: 'http-proxy', label: 'HTTP 代理' },
  { value: 'socks5-proxy', label: 'SOCKS5 代理' },
]

async function load() {
  loading.value = true
  try {
    const res = await pkSettingsApi.getTokenlabSettings()
    const data = res.data.data
    if (!data) return
    apiKeyConfigured.value = data.apiKeyConfigured === true
    form.value = {
      apiKey: '',
      baseUrl: data.baseUrl || 'https://api.tokenlab.cc.cd/v1',
      fallbackBaseUrl: data.fallbackBaseUrl || 'https://hk.tokenlab.ccwu.cc/v1',
      model: data.model || 'gpt-image-2',
      size: data.size || 'auto',
      quality: data.quality || 'high',
      network: data.network || 'direct',
      proxy: data.proxy || '',
    }
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const payload: PkTokenlabSettingsDTO = { ...form.value }
    if (!payload.apiKey?.trim()) {
      delete payload.apiKey
    }
    const res = await pkSettingsApi.saveTokenlabSettings(payload)
    apiKeyConfigured.value = res.data.data?.apiKeyConfigured === true
    form.value.apiKey = ''
    message.success('Tokenlab 配置已保存')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="pk-tokenlab-settings">
    <h2 class="settings-page-title">PatentKing 生图（Tokenlab）</h2>
    <p class="settings-page-desc">
      交底 Word 选择「自动生成」图示时，平台通过 Tokenlab <code>gpt-image-2</code> 接口生图。
      API Key 仅保存在租户配置中，不会写入日志或对话。
    </p>

    <ApboaSpin :spinning="loading">
      <AForm layout="vertical" class="pk-tokenlab-form">
        <AFormItem label="API Key" required>
          <AInputPassword
            v-model:value="form.apiKey"
            :placeholder="apiKeyConfigured ? '已配置（留空则不修改）' : '请输入 Tokenlab API Key'"
            autocomplete="new-password"
          />
          <div v-if="apiKeyConfigured" class="field-hint">当前租户已配置 Key；如需更换请填写新 Key 后保存。</div>
        </AFormItem>

        <AFormItem label="主 Base URL">
          <AInput v-model:value="form.baseUrl" placeholder="https://api.tokenlab.cc.cd/v1" />
        </AFormItem>

        <AFormItem label="备用 Base URL">
          <AInput v-model:value="form.fallbackBaseUrl" placeholder="https://hk.tokenlab.ccwu.cc/v1" />
        </AFormItem>

        <div class="form-row">
          <AFormItem label="模型" class="form-col">
            <AInput v-model:value="form.model" placeholder="gpt-image-2" />
          </AFormItem>
          <AFormItem label="尺寸" class="form-col">
            <ASelect v-model:value="form.size" :options="sizeOptions" />
            <div class="field-hint">选「按图示自动判断」时，导出阶段会按每张 mermaid 的方向与结构推断比例；也可在围栏前写 <code>&lt;!-- pk-diagram-size:1536x1024 --&gt;</code> 指定。</div>
          </AFormItem>
        </div>

        <div class="form-row">
          <AFormItem label="质量" class="form-col">
            <ASelect v-model:value="form.quality" :options="qualityOptions" />
          </AFormItem>
          <AFormItem label="网络模式" class="form-col">
            <ASelect v-model:value="form.network" :options="networkOptions" />
          </AFormItem>
        </div>

        <AFormItem
          v-if="form.network === 'http-proxy' || form.network === 'socks5-proxy'"
          label="代理地址"
        >
          <AInput v-model:value="form.proxy" placeholder="http://127.0.0.1:7890" />
        </AFormItem>

        <div class="form-actions">
          <AButton type="primary" :loading="saving" @click="save">保存配置</AButton>
        </div>
      </AForm>
    </ApboaSpin>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;

.pk-tokenlab-settings {
  max-width: 640px;
}

.settings-page-desc {
  color: var(--text-secondary, #666);
  margin-bottom: 24px;
  line-height: 1.6;
}

.field-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary, #888);
}

.form-row {
  display: flex;
  gap: 16px;
}

.form-col {
  flex: 1;
}

.form-actions {
  margin-top: 8px;
}
</style>
