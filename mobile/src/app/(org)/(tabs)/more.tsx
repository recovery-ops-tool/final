import React, { useState } from 'react';
import { View, ScrollView, TextInput, StyleSheet, TouchableOpacity } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Text } from '@/components/ui';
import { 
  Search, ChevronRight, Briefcase, Layers, Users, MapPin, Map, 
  AlertTriangle, ClipboardCheck, Calendar, TrendingUp, DollarSign, 
  Link, FileText, ShieldAlert, PieChart, History, Settings, UserPlus, 
  Shield, Building, Mail, CreditCard, HelpCircle
} from 'lucide-react-native';

interface MoreLink {
  label: string;
  path: string;
  icon: React.ElementType;
  roles?: string[];
}

interface MoreSection {
  title: string;
  links: MoreLink[];
}

export default function MoreScreen() {
  const { colors, spacing, radius } = useTheme();
  const { role } = useAuth();
  const router = useRouter();
  const [search, setSearch] = useState('');

  const allSections: MoreSection[] = [
    {
      title: 'Cases & Loans',
      links: [
        { label: 'My Cases', path: '/(org)/(tabs)/cases', icon: Briefcase, roles: ['FO', 'CALLER', 'TRACER'] },
        { label: 'Loans & Allocations', path: '/(org)/loans', icon: Layers, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Unassigned Cases', path: '/(org)/cases/unassigned', icon: HelpCircle, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Case Assignments', path: '/(org)/assignments', icon: UserPlus, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'CALLER', 'TRACER'] },
        { label: 'Borrowers', path: '/(org)/borrowers', icon: Users, roles: ['ORG_ADMIN'] },
      ]
    },
    {
      title: 'Collections & Payments',
      links: [
        { label: 'PTPs (Promise to Pay)', path: '/(org)/ptps', icon: TrendingUp, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'FO', 'CALLER', 'TRACER'] },
        { label: 'Collections', path: '/(org)/(tabs)/collections', icon: DollarSign, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'FO', 'CALLER', 'TRACER'] },
        { label: 'Collections Trend', path: '/(org)/collections/trend', icon: PieChart, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'CALLER', 'TRACER'] },
        { label: 'Payment Links', path: '/(org)/payments/links', icon: Link, roles: ['ORG_ADMIN'] },
        { label: 'Reconciliation', path: '/(org)/reconciliation', icon: FileText, roles: ['ORG_ADMIN'] },
      ]
    },
    {
      title: 'Field Operations',
      links: [
        { label: "Today's Visits", path: '/(org)/today', icon: Calendar, roles: ['FO', 'CALLER', 'TRACER'] },
        { label: 'Visited Logs', path: '/(org)/(tabs)/visited', icon: ClipboardCheck, roles: ['FO', 'CALLER', 'TRACER'] },
        { label: 'Daily Dispatch', path: '/(org)/dispatch', icon: MapPin, roles: ['MANAGER', 'TL'] },
        { label: 'Field Agents Roster', path: '/(org)/agents', icon: Users, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Live Track / Live Map', path: '/(org)/live-track', icon: Map, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Field Ops (SOS Monitor)', path: '/(org)/field-ops', icon: AlertTriangle, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'My Attendance', path: '/(org)/my-attendance', icon: ClipboardCheck, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'FO', 'CALLER', 'TRACER'] },
        { label: 'Team Attendance', path: '/(org)/attendance', icon: Calendar, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
      ]
    },
    {
      title: 'Reports & Compliance',
      links: [
        { label: 'Reports', path: '/(org)/reports', icon: FileText, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Audit Logs', path: '/(org)/audit', icon: History, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'CALLER', 'TRACER'] },
        { label: 'KPI Dashboard', path: '/(org)/kpi', icon: PieChart, roles: ['ORG_ADMIN'] },
      ]
    },
    {
      title: 'Team & Settings',
      links: [
        { label: 'User Setup', path: '/(org)/users', icon: UserPlus, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'User Requests', path: '/(org)/users/requests', icon: Mail, roles: ['ORG_ADMIN'] },
        { label: 'Role Management', path: '/(org)/settings/roles', icon: Shield, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Organization Settings', path: '/(org)/settings/organization', icon: Building, roles: ['ORG_ADMIN'] },
        { label: 'Message Templates', path: '/(org)/settings/message-templates', icon: Mail, roles: ['ORG_ADMIN'] },
        { label: 'Subscription & Billing', path: '/(org)/subscription', icon: CreditCard, roles: ['ORG_ADMIN'] },
        { label: 'Holiday Calendar', path: '/(org)/calendar', icon: Calendar, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Grievance Officer Settings', path: '/(org)/settings/grievance-officer', icon: Settings, roles: ['ORG_ADMIN', 'MANAGER', 'TL'] },
        { label: 'Profile Settings', path: '/(org)/(tabs)/profile', icon: Settings, roles: ['ORG_ADMIN', 'MANAGER', 'TL', 'FO', 'CALLER', 'TRACER'] },
      ]
    }
  ];

  // Filter sections by search and user role
  const filteredSections = allSections
    .map(section => {
      const links = section.links.filter(link => {
        // Role check
        if (link.roles && role && !link.roles.includes(role)) {
          return false;
        }
        // Search check
        if (search && !link.label.toLowerCase().includes(search.toLowerCase())) {
          return false;
        }
        return true;
      });
      return { ...section, links };
    })
    .filter(section => section.links.length > 0);

  return (
    <View style={[styles.container, { backgroundColor: colors.canvas, paddingTop: role === 'FO' ? 56 : 0 }]}>
      {/* Search Header */}
      {role !== 'FO' && (
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <Text variant="headline" style={styles.headerTitle}>More Features</Text>
          <View style={[styles.searchBar, { backgroundColor: colors.subtle, borderRadius: radius.md }]}>
            <Search size={20} color={colors.ink3} style={styles.searchIcon} />
            <TextInput
              placeholder="Search features..."
              placeholderTextColor={colors.ink3}
              value={search}
              onChangeText={setSearch}
              style={[styles.searchInput, { color: colors.ink1 }]}
            />
          </View>
        </View>
      )}

      <ScrollView contentContainerStyle={styles.scrollContent}>
        {filteredSections.map((section, idx) => (
          <View key={idx} style={styles.section}>
            <Text variant="caption" color="secondary" style={styles.sectionTitle}>
              {section.title.toUpperCase()}
            </Text>
            <View style={[styles.card, { backgroundColor: colors.surface, borderRadius: radius.md, borderColor: colors.border }]}>
              {section.links.map((link, lIdx) => {
                const Icon = link.icon;
                return (
                  <TouchableOpacity
                    key={lIdx}
                    onPress={() => router.push(link.path as any)}
                    style={[
                      styles.linkItem,
                      lIdx > 0 && { borderTopWidth: 1, borderTopColor: colors.border }
                    ]}
                  >
                    <View style={styles.linkLeft}>
                      <Icon size={20} color={colors.accent} style={styles.linkIcon} />
                      <Text variant="body" color="primary">{link.label}</Text>
                    </View>
                    <ChevronRight size={18} color={colors.ink3} />
                  </TouchableOpacity>
                );
              })}
            </View>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    paddingHorizontal: 16,
    paddingTop: 56,
    paddingBottom: 16,
    borderBottomWidth: 1,
  },
  headerTitle: {
    marginBottom: 12,
  },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    height: 40,
  },
  searchIcon: {
    marginRight: 8,
  },
  searchInput: {
    flex: 1,
    fontSize: 14,
    fontFamily: 'Inter_400Regular',
    padding: 0,
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 40,
  },
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    marginBottom: 8,
    marginLeft: 4,
    letterSpacing: 1,
  },
  card: {
    borderWidth: 1,
    overflow: 'hidden',
  },
  linkItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
    paddingHorizontal: 16,
  },
  linkLeft: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  linkIcon: {
    marginRight: 12,
  },
});
