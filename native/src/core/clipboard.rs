use anyhow::Result;
use arboard::Clipboard;
use std::sync::Arc;
use tokio::sync::Mutex;
use tracing::debug;

pub struct ClipboardManager {
    clipboard: Arc<Mutex<Clipboard>>,
    original_content: Arc<Mutex<Option<String>>>,
}

impl ClipboardManager {
    pub fn new() -> Result<Self> {
        Ok(Self {
            clipboard: Arc::new(Mutex::new(Clipboard::new()?)),
            original_content: Arc::new(Mutex::new(None)),
        })
    }

    pub async fn get_text(&self) -> Result<String> {
        let mut clipboard = self.clipboard.lock().await;
        clipboard
            .get_text()
            .map_err(|e| anyhow::anyhow!("Failed to get clipboard text: {}", e))
    }

    pub async fn set_text(&self, text: String) -> Result<()> {
        let mut clipboard = self.clipboard.lock().await;
        clipboard
            .set_text(text)
            .map_err(|e| anyhow::anyhow!("Failed to set clipboard text: {}", e))
    }

    pub async fn save_clipboard(&self) -> Result<()> {
        debug!("Saving clipboard content");
        let content = self.get_text().await.ok();
        let mut original = self.original_content.lock().await;
        *original = content;
        Ok(())
    }

    pub async fn restore_clipboard(&self) -> Result<()> {
        debug!("Restoring clipboard content");
        let original = self.original_content.lock().await;
        if let Some(content) = original.clone() {
            drop(original); // Release lock before calling set_text
            self.set_text(content).await?;
        }
        Ok(())
    }

    pub async fn replace_text(&self, new_text: String) -> Result<()> {
        debug!("Replacing text via clipboard");

        // Set new text to clipboard
        self.set_text(new_text).await?;

        // Small delay for stability
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;

        // Simulate paste
        let mut simulator = super::KeySimulator::new()?;
        simulator.paste()?;

        Ok(())
    }
}
