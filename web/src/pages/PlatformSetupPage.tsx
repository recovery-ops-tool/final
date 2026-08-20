import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus } from 'lucide-react';
import { platformApi } from '../api/platformApi';
import type { OrganizationSummary } from '../api/platformApi';
import { PageFab } from '../components/PageFab';
import { OrgsTab } from './PlatformSetupOrgsTab';
import { UsersTab } from './PlatformSetupUsersTab';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

type Tab = 'orgs' | 'users';

export default function PlatformSetupPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as Tab) || 'orgs';
  const [orgs, setOrgs] = useState<OrganizationSummary[]>([]);
  const [showCreateOrg, setShowCreateOrg] = useState(false);
  const [showCreateUser, setShowCreateUser] = useState(false);

  const loadOrgs = useCallback(async () => {
    try { setOrgs(await platformApi.listOrganizations()); } catch { /* silent */ }
  }, []);

  useEffect(() => { loadOrgs(); }, [loadOrgs]);
  useEffect(() => { setShowCreateOrg(false); setShowCreateUser(false); }, [tab]);

  return (
    <div className="db-root">
      <div className="db-content">
        <motion.div className="db-inner" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}>
          <div className="db-page-header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16, marginBottom: 20 }}>
            <p className="dd-page-context" style={{ margin: 0 }}>
              Manage organizations and platform users
            </p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
              <div className="db-kpi-toggle" role="group" aria-label="Setup view">
                <button
                  type="button"
                  className={`db-kpi-toggle-btn${tab === 'orgs' ? ' is-active' : ''}`}
                  onClick={() => setSearchParams({ tab: 'orgs' })}
                  aria-pressed={tab === 'orgs'}
                >
                  Organizations
                </button>
                <button
                  type="button"
                  className={`db-kpi-toggle-btn${tab === 'users' ? ' is-active' : ''}`}
                  onClick={() => setSearchParams({ tab: 'users' })}
                  aria-pressed={tab === 'users'}
                >
                  Users
                </button>
              </div>
            </div>
          </div>
          <AnimatePresence mode="wait">
            <motion.div
              key={tab}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2 }}
            >
              {tab === 'orgs'
                ? <OrgsTab orgs={orgs} onOrgsChange={setOrgs} showCreate={showCreateOrg} setShowCreate={setShowCreateOrg} />
                : <UsersTab orgs={orgs} showCreate={showCreateUser} setShowCreate={setShowCreateUser} />}
            </motion.div>
          </AnimatePresence>
        </motion.div>
      </div>
      {(tab === 'orgs' || tab === 'users') && (
        <PageFab
          icon={<Plus size={24} />}
          label={tab === 'orgs' ? 'New organization' : 'New admin user'}
          onClick={() => tab === 'orgs' ? setShowCreateOrg(true) : setShowCreateUser(true)}
        />
      )}
    </div>
  );
}
