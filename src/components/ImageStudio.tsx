import { useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import { parseImageMessage } from '../../shared/types';
import { useChatStore } from '../store/chatStore';
import { useSettingsStore } from '../store/settingsStore';
import { MessageBubble } from './MessageBubble';

const SIZE_OPTIONS = ['256', '512', '768', '1024'];

export function ImageStudio() {
  const [mode, setMode] = useState('generate');
  const [steps, setSteps] = useState(20);
  const [cfgScale, setCfgScale] = useState(2.5);
  const [width, setWidth] = useState('512');
  const [height, setHeight] = useState('512');
  const [seed, setSeed] = useState(42);
  const [upscale, setUpscale] = useState('off');
  const [prompt, setPrompt] = useState('');
  const [relativePath, setRelativePath] = useState('images/generated.png');
  const [saveToFolder, setSaveToFolder] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  const messages = useChatStore((state) => state.messages);
  const generateImage = useChatStore((state) => state.generateImage);
  const generatingImage = useChatStore((state) => state.generatingImage);
  const error = useChatStore((state) => state.error);
  const modelStatus = useChatStore((state) => state.modelStatus);
  const conversations = useChatStore((state) => state.conversations);
  const activeConversationId = useChatStore((state) => state.activeConversationId);
  const setConversationModel = useChatStore((state) => state.setConversationModel);
  const settings = useSettingsStore((state) => state.settings);
  const imageModels = useSettingsStore((state) => state.imageModels);

  const active = conversations.find((item) => item.id === activeConversationId);
  const hasFolder = Boolean(active?.workspacePath);
  const models = imageModels();
  const activeModel = active?.model ?? settings?.imageModel ?? '';
  const canGenerate = Boolean(prompt.trim() && (activeModel || models[0]));

  const imageMessages = useMemo(
    () => messages.filter((message) => parseImageMessage(message.content)),
    [messages],
  );

  useEffect(() => {
    if (!hasFolder) {
      setSaveToFolder(false);
    }
  }, [hasFolder, activeConversationId]);

  const onGenerate = async () => {
    if (!canGenerate || generatingImage) return;
    setMenuOpen(false);
    await generateImage({
      prompt,
      size: `${width}x${height}`,
      steps,
      cfgScale,
      seed,
      saveToWorkspacePath: saveToFolder && hasFolder ? relativePath.trim() || null : null,
    });
    setPrompt('');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void onGenerate();
    }
  };

  return (
    <div className="image-studio">
      <div className="image-studio-scroll">
        {imageMessages.length ? (
          <div className="image-studio-gallery">
            {imageMessages.map((message) => (
              <MessageBubble key={message.id} message={message} />
            ))}
          </div>
        ) : null}

        <div className="image-studio-panel">
          <div className="image-studio-brand">
            <h1>Image Generator</h1>
            <div className={`image-studio-status ${generatingImage || modelStatus ? 'busy' : ''}`}>
              {generatingImage || modelStatus ? (
                <>
                  <span className="image-spinner" />
                  {modelStatus || 'Generating image…'}
                </>
              ) : (
                <span className="image-studio-status-idle">Ready</span>
              )}
            </div>
          </div>

          <div className="image-param-grid">
            <label className="image-param">
              <span>Mode</span>
              <select
                value={mode}
                onChange={(event) => setMode(event.target.value)}
                disabled={generatingImage}
              >
                <option value="generate">Generate</option>
              </select>
            </label>
            <label className="image-param">
              <span>Steps</span>
              <input
                type="number"
                min={1}
                max={100}
                value={steps}
                onChange={(event) => setSteps(Number(event.target.value) || 1)}
                disabled={generatingImage}
              />
            </label>
            <label className="image-param">
              <span>CFG Scale</span>
              <input
                type="number"
                min={0}
                max={30}
                step={0.1}
                value={cfgScale}
                onChange={(event) => setCfgScale(Number(event.target.value) || 0)}
                disabled={generatingImage}
              />
            </label>
            <label className="image-param">
              <span>Width</span>
              <select
                value={width}
                onChange={(event) => setWidth(event.target.value)}
                disabled={generatingImage}
              >
                {SIZE_OPTIONS.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="image-param">
              <span>Height</span>
              <select
                value={height}
                onChange={(event) => setHeight(event.target.value)}
                disabled={generatingImage}
              >
                {SIZE_OPTIONS.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="image-param">
              <span>Seed</span>
              <input
                type="number"
                value={seed}
                onChange={(event) => setSeed(Number(event.target.value) || 0)}
                disabled={generatingImage}
              />
            </label>
          </div>

          <div className="image-param-row-center">
            <label className="image-param">
              <span>Upscale</span>
              <select
                value={upscale}
                onChange={(event) => setUpscale(event.target.value)}
                disabled={generatingImage}
              >
                <option value="off">Off</option>
              </select>
            </label>
          </div>

          {error ? <div className="error-banner image-studio-error">{error}</div> : null}

          <div className="image-prompt-shell">
            <textarea
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Describe the image you want to generate..."
              disabled={generatingImage}
            />
            <div className="image-prompt-toolbar">
              <select
                className="image-model-chip"
                value={activeModel}
                onChange={(event) => void setConversationModel(event.target.value)}
                disabled={generatingImage || !models.length}
                title="Image model"
              >
                {!models.length && <option value="">No models</option>}
                {models.map((model) => (
                  <option key={model.id} value={model.id}>
                    {model.id}
                  </option>
                ))}
              </select>

              <div className="image-prompt-actions">
                <div className="image-menu-wrap">
                  <button
                    type="button"
                    className="image-menu-btn"
                    aria-label="More options"
                    disabled={generatingImage}
                    onClick={() => setMenuOpen((open) => !open)}
                  >
                    ⋯
                  </button>
                  {menuOpen ? (
                    <div className="image-menu-pop">
                      <label className="check-row">
                        <input
                          type="checkbox"
                          checked={saveToFolder && hasFolder}
                          disabled={!hasFolder}
                          onChange={(event) => setSaveToFolder(event.target.checked)}
                        />
                        Save to folder{!hasFolder ? ' (no folder)' : ''}
                      </label>
                      {saveToFolder && hasFolder ? (
                        <input
                          className="text-input"
                          value={relativePath}
                          onChange={(event) => setRelativePath(event.target.value)}
                          placeholder="images/generated.png"
                        />
                      ) : null}
                      {hasFolder ? <p className="hint">{active?.workspacePath}</p> : null}
                    </div>
                  ) : null}
                </div>
                <button
                  type="button"
                  className="btn btn-primary image-generate-btn"
                  disabled={!canGenerate || generatingImage}
                  onClick={() => void onGenerate()}
                >
                  {generatingImage ? 'Generating…' : 'Generate'}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
