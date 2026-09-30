/**
 * 系统设置左侧菜单组件 — 含租户管理菜单
 *
 * @author huxuehao
 */
<script setup lang="ts">
import { computed, type Component } from 'vue'
import {
  UserOutlined,
  InfoCircleOutlined,
  DatabaseOutlined,
  ControlOutlined,
  KeyOutlined,
  ApartmentOutlined,
  GlobalOutlined,
  CloudServerOutlined,
  PictureOutlined
} from '@ant-design/icons-vue'
import { useAccountStore } from '@/stores'

defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const accountStore = useAccountStore()
const isAdmin = computed(() => accountStore.isAdmin)

const menuItems = computed(() => {
  const items: { key: string; label: string; icon: Component }[] = [
    { key: 'myAccount', label: '我的账户', icon: UserOutlined }
  ]

  if (isAdmin.value) {
    items.push({ key: 'tenantSettings', label: '组织管理', icon: ApartmentOutlined })
  }

  items.push({ key: 'tenantDiscovery', label: '发现组织', icon: GlobalOutlined })

  if (isAdmin.value) {
    items.push(
      // { key: 'allAccounts', label: '全部账户', icon: TeamOutlined },
      { key: 'apiKeys', label: 'API Keys', icon: KeyOutlined },
      { key: 'pkTokenlab', label: 'PatentKing 生图', icon: PictureOutlined },
      { key: 'storageManagement', label: '存储管理', icon: DatabaseOutlined },
      { key: 'systemParams', label: '系统参数', icon: ControlOutlined },
      { key: 'executionNodes', label: '服务监控', icon: CloudServerOutlined }
    )
  }

  items.push({ key: 'systemIntro', label: '系统介绍', icon: InfoCircleOutlined })

  return items
})

function handleMenuClick(key: string) {
  emit('update:modelValue', key)
}
</script>

<template>
  <div class="settings-menu">
    <div class="settings-menu-title">系统设置</div>
    <div class="settings-menu-list">
      <div
        v-for="item in menuItems"
        :key="item.key"
        class="settings-menu-item"
        :class="{ active: modelValue === item.key }"
        @click="handleMenuClick(item.key)"
      >
        <component :is="item.icon" class="settings-menu-icon" />
        <span class="settings-menu-label">{{ item.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/modules/_settings.scss' as *;
</style>
