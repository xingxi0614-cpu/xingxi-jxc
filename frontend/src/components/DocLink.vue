<template>
  <button
    type="button"
    class="doc-link"
    aria-label="查看单据"
    :title="`查看单据 ${docNo}`"
    @click.stop="onClick"
  >
    {{ docNo }}
  </button>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const props = defineProps<{ docNo: string }>()
const emit = defineEmits<{ (e: 'click', no: string): void }>()
const router = useRouter()

function onClick() {
  emit('click', props.docNo)
  router.push({ path: '/trace/chain', query: { docNo: props.docNo } })
}
</script>
