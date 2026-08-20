import { useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { UserCheck, CheckCircle2, Users, Briefcase, User } from 'lucide-react';
import type { UserResponse } from '../types';
import { hashColor } from '../utils/navConfig';
import { DonutCard } from './DashboardShared';
import './Dashboard.css';

interface Props {
  fos: UserResponse[];
  fosLoading: boolean;
  fosStats: Map<string, { count: number; value: number; pending: number }>;
  selectedFo: string;
  onSelect: (id: string) => void;
}

const initials = (f: UserResponse) =>
  `${f.firstName?.[0] ?? ''}${f.lastName?.[0] ?? ''}`.toUpperCase();

export default function AssignFoPanel({ fos, fosLoading, fosStats, selectedFo, onSelect }: Props) {

  return (
    <div className="dd-officers-card ds-card is-overflow-hidden">
      <div className="dd-ap-header db-card-head">
        <h2 className="dd-ap-header-label db-list-title">
          <Users size={12} aria-hidden="true" />
          Field Officers
        </h2>
        {!fosLoading && (
          <span className="dd-cp-tab-count" style={{ marginLeft: 'auto' }}>
            {fos.length}
          </span>
        )}
      </div>

      <div className="dd-agent-list">
        {fosLoading ? (
          Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="dd-agent-skel" style={{ opacity: 1 - i * 0.12 }}>
              <span className="ds-skel" style={{ width: 32, height: 32, borderRadius: '50%', flexShrink: 0 }} />
              <span className="ds-skel" style={{ height: 13, flex: 1 }} />
            </div>
          ))
        ) : fos.length === 0 ? (
          <div className="ds-empty" style={{ padding: '40px 0' }}>
            <UserCheck size={22} className="ds-empty-icon" />
            <span className="ds-empty-title">No officers match</span>
          </div>
        ) : (
          <div className="dd-agent-list" style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '12px 0' }}>
            {fos.map(f => {
              const isActive = selectedFo === f.id;
              const stats = fosStats.get(f.id);
              const count = stats?.count || 0;
              return (
                <button
                  key={f.id}
                  type="button"
                  className={`dd-agent-row${isActive ? ' is-active' : ''}`}
                  onClick={() => onSelect(f.id)}
                >
                  <span
                    className="dd-fo-avatar"
                    style={{ background: hashColor(`${f.firstName}${f.lastName}`), color: 'var(--text-on-solid)', border: 'none' }}
                  >
                    {initials(f) || <User size={14} />}
                  </span>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', textAlign: 'left' }}>
                    <span className="dd-agent-row-name">
                      {`${f.firstName} ${f.lastName}`.trim()}
                    </span>
                  </div>
                  <span className="dd-cp-tab-count" style={{ marginLeft: 'auto', marginRight: 8, opacity: count > 0 ? 1 : 0.4 }}>
                    {count} cases
                  </span>
                  {isActive && (
                    <CheckCircle2 size={14} className="dd-agent-row-check" aria-hidden="true" />
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      <AnimatePresence mode="wait">
        {selectedFo && !fosLoading && (
          <motion.div
            key={selectedFo}
            className="dd-agent-stats-footer"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.22 }}
          >
            <div className="dd-dispatch-ring-wrap">
              {/* Same donut + legend as the dashboard's "Case assignment" card. */}
              <DonutCard
                slices={[
                  { label: 'Pending', value: fosStats.get(selectedFo)?.pending ?? 0, color: 'var(--dbc-1)' },
                  { label: 'Closed',  value: Math.max(0, (fosStats.get(selectedFo)?.count ?? 0) - (fosStats.get(selectedFo)?.pending ?? 0)), color: 'var(--dbn-2)' },
                ]}
                centerLabel="CASES"
                size={104}
              />
              <div className="dd-ap-stats">
                <div className="dd-ap-stat">
                  <span className="dd-ap-stat-num" style={{ fontSize: 13, letterSpacing: '-0.01em' }}>
                    {(() => {
                      const v = fosStats.get(selectedFo)?.value || 0;
                      if (v >= 1e7) return `₹${(v/1e7).toFixed(2)}Cr`;
                      if (v >= 1e5) return `₹${(v/1e5).toFixed(1)}L`;
                      if (v >= 1e3) return `₹${(v/1e3).toFixed(0)}K`;
                      return `₹${Math.round(v)}`;
                    })()}
                  </span>
                  <span className="dd-ap-stat-lbl">Portfolio</span>
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
