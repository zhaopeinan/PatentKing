<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import * as pkMatterApi from '@/api/pkMatter'
import type { PkMatter, PkMatterVO } from '@/types'
import {
  PK_DISCLOSURE_SCENES,
  PK_PRIMARY_MATTER_TYPES,
  matterTypeDisplay,
  mergeSceneIntoMeta,
} from '@/utils/pkMatterTypes'

const router = useRouter()
const loading = ref(false)
const records = ref<PkMatterVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, title: '', matterType: undefined as string | undefined, status: undefined as string | undefined })

const MATTER_TYPES = PK_PRIMARY_MATTER_TYPES.map(({ label, value }) => ({ label, value }))

const STATUSES = [
  { label: '草稿', value: 'draft' },
  { label: '进行中', value: 'in_progress' },
  { label: '待确认', value: 'awaiting_confirm' },
  { label: '已交付', value: 'delivered' },
  { label: '已归档', value: 'archived' },
]

const statusLabel = (v?: string) => STATUSES.find((i) => i.value === v)?.label || v || '-'

async function load() {
  loading.value = true
  try {
    const res = await pkMatterApi.page(query)
    const page = res.data.data
    records.value = page?.records || []
    total.value = page?.total || 0
  } finally {
    loading.value = false
  }
}

const formOpen = ref(false)
const form = reactive({
  title: '',
  matterType: 'disclosure',
  scene: 'write',
  status: 'draft',
  remark: '',
})

const showScene = computed(() => form.matterType === 'disclosure')
const typeHint = computed(() =>
  PK_PRIMARY_MATTER_TYPES.find(t => t.value === form.matterType)?.hint || '',
)

function openCreate() {
  form.title = ''
  form.matterType = 'disclosure'
  form.scene = 'write'
  form.status = 'draft'
  form.remark = ''
  formOpen.value = true
}

async function submitCreate() {
  if (!form.title?.trim()) {
    message.warning('请填写案件标题')
    return
  }
  const scene = form.matterType === 'disclosure' ? form.scene : (form.matterType === 'oa' ? 'oa' : 'write')
  const payload: PkMatter = {
    title: form.title.trim(),
    matterType: form.matterType,
    status: form.status,
    remark: form.remark,
    metaJson: mergeSceneIntoMeta(undefined, scene),
  }
  await pkMatterApi.save(payload)
  message.success('已创建')
  formOpen.value = false
  await load()
}

function goDetail(row: PkMatterVO) {
  router.push(`/patent-matters/${row.id}`)
}

function confirmDelete(row: PkMatterVO) {
  Modal.confirm({
    title: '删除案件',
    content: `确定删除「${row.title}」？版本与产物会一并删除。`,
    onOk: async () => {
      await pkMatterApi.remove([row.id])
      message.success('已删除')
      await load()
    },
  })
}

onMounted(load)
</script>

<template>
  <div class="pk-matter-page">
    <div class="toolbar">
      <a-input-search
        v-model:value="query.title"
        placeholder="搜索案件标题"
        style="width: 240px"
        allow-clear
        @search="load"
      />
      <a-select
        v-model:value="query.matterType"
        allow-clear
        placeholder="类型"
        style="width: 160px"
        :options="MATTER_TYPES"
        @change="load"
      />
      <a-select
        v-model:value="query.status"
        allow-clear
        placeholder="状态"
        style="width: 140px"
        :options="STATUSES"
        @change="load"
      />
      <a-button type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>
        新建案件
      </a-button>
    </div>

    <a-table
      row-key="id"
      :loading="loading"
      :data-source="records"
      :pagination="{ current: query.page, pageSize: query.size, total, showSizeChanger: true }"
      @change="(p: any) => { query.page = p.current; query.size = p.pageSize; load() }"
    >
      <a-table-column title="标题" data-index="title">
        <template #default="{ record }">
          <a @click="goDetail(record)">{{ record.title }}</a>
        </template>
      </a-table-column>
      <a-table-column title="类型" :width="180">
        <template #default="{ record }">
          {{ matterTypeDisplay(record.matterType, record.metaJson) }}
        </template>
      </a-table-column>
      <a-table-column title="状态" :width="120">
        <template #default="{ record }">{{ statusLabel(record.status) }}</template>
      </a-table-column>
      <a-table-column title="更新时间" data-index="updatedAt" :width="180" />
      <a-table-column title="操作" :width="140">
        <template #default="{ record }">
          <a-space>
            <a @click="goDetail(record)">详情</a>
            <a class="danger" @click="confirmDelete(record)">删除</a>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="formOpen" title="新建案件" ok-text="创建" @ok="submitCreate">
      <a-form layout="vertical">
        <a-form-item label="标题" required>
          <a-input v-model:value="form.title" placeholder="例如：智能调度系统交底" />
        </a-form-item>
        <a-form-item label="类型">
          <a-select
            v-model:value="form.matterType"
            :options="MATTER_TYPES"
            @change="() => { form.scene = 'write' }"
          />
          <div v-if="typeHint" class="field-hint">{{ typeHint }}</div>
        </a-form-item>
        <a-form-item v-if="showScene" label="场景（可选，对话里仍可改口）">
          <a-select
            v-model:value="form.scene"
            :options="PK_DISCLOSURE_SCENES.map(s => ({ label: s.label, value: s.value }))"
          />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model:value="form.remark" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.pk-matter-page {
  padding: 16px 20px;
}
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
}
.danger {
  color: #ff4d4f;
}
.field-hint {
  margin-top: 6px;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.4;
}
</style>
