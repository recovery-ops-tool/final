import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import './PageFab.css';

interface PageFabProps {
  icon: ReactNode;
  label: string;
  onClick: () => void;
}

// Bottom-right circular action button shared by admin list pages (new org,
// new flag, upload, new document, new prompt, ...).
//
// Portaled to <body> for the same reason the sidebar's rail tooltip is (see
// AppSidebar.tsx): anything in the page tree between here and the viewport
// that sets transform/filter/contain — framer-motion's animated wrappers,
// .app-route-view's transform keyframes — becomes the containing block for
// a `position: fixed` descendant, which makes this button scroll along with
// the card instead of staying pinned to the screen. Portaling escapes that
// entirely, so `fixed` resolves against the viewport as intended.
export function PageFab({ icon, label, onClick }: PageFabProps) {
  return createPortal(
    <button
      type="button"
      className="ds-fab"
      onClick={onClick}
      title={label}
      aria-label={label}
    >
      {icon}
    </button>,
    document.body,
  );
}
