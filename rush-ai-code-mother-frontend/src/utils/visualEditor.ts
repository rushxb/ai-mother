/** 可视化编辑器与受限 iframe 之间的消息桥。 */
export interface ElementInfo {
  tagName: string
  id: string
  className: string
  textContent: string
  selector: string
  pagePath: string
  rect: {
    top: number
    left: number
    width: number
    height: number
  }
}

export interface VisualEditorOptions {
  onElementSelected?: (elementInfo: ElementInfo) => void
  onElementHover?: (elementInfo: ElementInfo) => void
}

type EditorMessage = Readonly<Record<string, unknown>> & { type: string }

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

const isFiniteNumber = (value: unknown): value is number => {
  return typeof value === 'number' && Number.isFinite(value)
}

const isElementInfo = (value: unknown): value is ElementInfo => {
  if (!isRecord(value) || !isRecord(value.rect)) {
    return false
  }
  const rect = value.rect
  return ['tagName', 'id', 'className', 'textContent', 'selector', 'pagePath'].every(
    (key) => typeof value[key] === 'string',
  ) && ['top', 'left', 'width', 'height'].every((key) => isFiniteNumber(rect[key]))
}

const createChannelId = () => {
  return globalThis.crypto?.randomUUID?.() || `visual-editor-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export class VisualEditor {
  private iframe: HTMLIFrameElement | null = null
  private isEditMode = false
  private readonly options: VisualEditorOptions
  private channelId = createChannelId()

  constructor(options: VisualEditorOptions = {}) {
    this.options = options
  }

  init(iframe: HTMLIFrameElement) {
    if (this.iframe !== iframe) {
      this.channelId = createChannelId()
    }
    this.iframe = iframe
  }

  dispose() {
    this.isEditMode = false
    this.iframe = null
    this.channelId = createChannelId()
  }

  enableEditMode() {
    if (!this.iframe) {
      return
    }
    this.isEditMode = true
    window.setTimeout(() => this.injectEditScript(), 300)
  }

  disableEditMode() {
    this.isEditMode = false
    this.sendMessageToIframe({ type: 'TOGGLE_EDIT_MODE', editMode: false })
    this.sendMessageToIframe({ type: 'CLEAR_ALL_EFFECTS' })
  }

  toggleEditMode() {
    if (this.isEditMode) {
      this.disableEditMode()
    } else {
      this.enableEditMode()
    }
    return this.isEditMode
  }

  syncState() {
    if (!this.isEditMode) {
      this.sendMessageToIframe({ type: 'CLEAR_ALL_EFFECTS' })
    }
  }

  clearSelection() {
    this.sendMessageToIframe({ type: 'CLEAR_SELECTION' })
  }

  onIframeLoad() {
    window.setTimeout(() => {
      if (this.isEditMode) {
        this.injectEditScript()
      } else {
        this.syncState()
      }
    }, 500)
  }

  handleIframeMessage(event: MessageEvent) {
    if (!this.iframe?.contentWindow || event.source !== this.iframe.contentWindow || !isRecord(event.data)) {
      return
    }
    if (event.data.channelId !== this.channelId || typeof event.data.type !== 'string') {
      return
    }
    const data = event.data.data
    if (!isRecord(data) || !isElementInfo(data.elementInfo)) {
      return
    }
    if (event.data.type === 'ELEMENT_SELECTED') {
      this.options.onElementSelected?.(data.elementInfo)
    } else if (event.data.type === 'ELEMENT_HOVER') {
      this.options.onElementHover?.(data.elementInfo)
    }
  }

  private sendMessageToIframe(message: EditorMessage) {
    if (!this.iframe?.contentWindow) {
      return
    }
    // iframe 未授予 allow-same-origin，其安全沙箱来源为 opaque origin，浏览器要求使用 *。
    // 安全性由专属 channelId、event.source 校验和 iframe sandbox 共同保证。
    this.iframe.contentWindow.postMessage({ ...message, channelId: this.channelId }, '*')
  }

  private injectEditScript() {
    if (!this.iframe) {
      return
    }
    this.sendMessageToIframe({
      type: 'INJECT_EDIT_SCRIPT',
      script: this.generateEditScript(),
    })
    this.sendMessageToIframe({ type: 'TOGGLE_EDIT_MODE', editMode: true })
  }

  private generateEditScript() {
    const parentOrigin = typeof window === 'undefined' ? '' : window.location.origin
    const serializedOrigin = JSON.stringify(parentOrigin)
    const serializedChannelId = JSON.stringify(this.channelId)

    return `
(function () {
  const parentOrigin = ${serializedOrigin};
  const channelId = ${serializedChannelId};
  const bridgeKey = '__rushVisualEditorBridge';
  const previousBridge = window[bridgeKey];
  if (previousBridge && typeof previousBridge.dispose === 'function') previousBridge.dispose();

  let editMode = true;
  let hoverElement = null;
  let selectedElement = null;

  const clearHover = () => {
    if (hoverElement) hoverElement.classList.remove('rush-edit-hover');
    hoverElement = null;
  };
  const clearSelection = () => {
    document.querySelectorAll('.rush-edit-selected').forEach((element) => element.classList.remove('rush-edit-selected'));
    selectedElement = null;
  };
  const clearAll = () => {
    editMode = false;
    clearHover();
    clearSelection();
    document.getElementById('rush-edit-tip')?.remove();
  };

  const ensureStyles = () => {
    if (document.getElementById('rush-edit-styles')) return;
    const style = document.createElement('style');
    style.id = 'rush-edit-styles';
    style.textContent = '.rush-edit-hover{outline:2px dashed #1890ff!important;outline-offset:2px!important;cursor:crosshair!important}.rush-edit-selected{outline:3px solid #52c41a!important;outline-offset:2px!important}';
    document.head.appendChild(style);
  };

  const selectorFor = (element) => {
    const path = [];
    let current = element;
    while (current && current !== document.body) {
      let selector = current.tagName.toLowerCase();
      if (current.id) {
        path.unshift(selector + '#' + CSS.escape(current.id));
        break;
      }
      const classes = Array.from(current.classList || []).filter((name) => !name.startsWith('rush-edit-')).slice(0, 3);
      if (classes.length) selector += '.' + classes.map((name) => CSS.escape(name)).join('.');
      const siblings = Array.from(current.parentElement?.children || []);
      selector += ':nth-child(' + (siblings.indexOf(current) + 1) + ')';
      path.unshift(selector);
      current = current.parentElement;
    }
    return path.join(' > ');
  };

  const elementInfoFor = (element) => {
    const rect = element.getBoundingClientRect();
    return {
      tagName: String(element.tagName || ''),
      id: String(element.id || ''),
      className: String(element.className || ''),
      textContent: String(element.textContent || '').trim().slice(0, 100),
      selector: selectorFor(element),
      pagePath: window.location.search + window.location.hash,
      rect: { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
    };
  };

  const isSelectable = (target) => target instanceof Element && target !== document.body && target !== document.documentElement && !['SCRIPT', 'STYLE'].includes(target.tagName);
  const onMouseOver = (event) => {
    if (!editMode || !isSelectable(event.target) || event.target === selectedElement) return;
    clearHover();
    event.target.classList.add('rush-edit-hover');
    hoverElement = event.target;
  };
  const onMouseOut = (event) => {
    if (editMode && (!event.relatedTarget || !event.target.contains(event.relatedTarget))) clearHover();
  };
  const onClick = (event) => {
    if (!editMode || !isSelectable(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
    clearSelection();
    clearHover();
    event.target.classList.add('rush-edit-selected');
    selectedElement = event.target;
    window.parent.postMessage({ type: 'ELEMENT_SELECTED', channelId, data: { elementInfo: elementInfoFor(event.target) } }, parentOrigin);
  };
  const onMessage = (event) => {
    if (event.source !== window.parent || event.origin !== parentOrigin || !event.data || event.data.channelId !== channelId) return;
    switch (event.data.type) {
      case 'TOGGLE_EDIT_MODE': editMode = event.data.editMode === true; if (!editMode) { clearHover(); clearSelection(); } break;
      case 'CLEAR_SELECTION': clearSelection(); break;
      case 'CLEAR_ALL_EFFECTS': clearAll(); break;
    }
  };

  ensureStyles();
  document.addEventListener('mouseover', onMouseOver, true);
  document.addEventListener('mouseout', onMouseOut, true);
  document.addEventListener('click', onClick, true);
  window.addEventListener('message', onMessage);
  window[bridgeKey] = {
    dispose() {
      clearAll();
      document.removeEventListener('mouseover', onMouseOver, true);
      document.removeEventListener('mouseout', onMouseOut, true);
      document.removeEventListener('click', onClick, true);
      window.removeEventListener('message', onMessage);
    }
  };
})();`
  }
}
