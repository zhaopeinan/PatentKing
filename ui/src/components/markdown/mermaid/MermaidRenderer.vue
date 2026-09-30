<template>
  <!-- 流式未完成 → 骨架屏 -->
  <MermaidSkeleton v-if="isStreaming" />
  <!-- 流式完成 → 图表 -->
  <MermaidGraph
    v-else
    :code="code"
    :disabled="disabled"
    :diagram-mode="diagramMode"
    @retry="emit('retry', $event)"
  />
</template>

<script setup lang="ts">
import MermaidSkeleton from '@/components/markdown/mermaid/MermaidSkeleton.vue'
import MermaidGraph from '@/components/markdown/mermaid/MermaidGraph.vue'
import type { MermaidRetryPayload } from '@/utils/chat/mermaid'

defineProps<{
  code: string
  isStreaming?: boolean
  disabled?: boolean
  diagramMode?: string
}>()

const emit = defineEmits<{
  retry: [payload: MermaidRetryPayload]
}>()
</script>

<style scoped>
</style>
