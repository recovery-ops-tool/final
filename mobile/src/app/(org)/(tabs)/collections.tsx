import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TouchableOpacity, Share, Modal, Pressable, ScrollView, SafeAreaView, TextInput } from 'react-native';
import { useFocusEffect, router } from 'expo-router';
import { DollarSign, WifiOff, X, Banknote, FileText, CreditCard, Calendar, User, CheckCircle2, Download, Search } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Card, Badge, EmptyState, LoadingView, Divider, Button } from '@/components/ui';
import { collectionsApi } from '@/api/collectionsApi';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate, formatDateTime } from '@/utils/date';
import { useToast } from '@/context/ToastContext';
import type { CollectionResponse } from '@/types/domain';

export default function CollectionsHubScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [collections, setCollections] = useState<CollectionResponse[]>([]);
  const [loadError, setLoadError] = useState(false);
  const [search, setSearch] = useState('');
  
  const filteredCollections = collections.filter(c => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return c.id?.toLowerCase().includes(q) || 
           c.status?.toLowerCase().includes(q) || 
           c.paymentMode?.toLowerCase().includes(q) ||
           (c.submittedBy || '').toLowerCase().includes(q);
  });
  
  const [selectedCol, setSelectedCol] = useState<CollectionResponse | null>(null);
  const [showExportModal, setShowExportModal] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await collectionsApi.list({ size: 100 });
      setCollections(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const filterAndShareCSV = async (type: 'ALL' | 'TODAY' | 'MONTH' | 'YEAR') => {
    const d = new Date();
    // Use local date string to match basic logic
    const todayStr = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
    const monthStartStr = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-01`;
    const yearStartStr = `${d.getFullYear()}-01-01`;

    let filtered = collections;
    if (type === 'TODAY') filtered = collections.filter(c => c.collectionDate >= todayStr);
    else if (type === 'MONTH') filtered = collections.filter(c => c.collectionDate >= monthStartStr);
    else if (type === 'YEAR') filtered = collections.filter(c => c.collectionDate >= yearStartStr);

    try {
      const header = 'ID,Amount,Status,Date,Mode\n';
      const rows = filtered
        .map((c) => `${c.id},${c.amount},${c.status},${c.collectionDate},${c.paymentMode}`)
        .join('\n');
      await Share.share({
        message: header + rows,
        title: 'Export Collections',
      });
    } catch (err) {
      showToast('Failed to export collections', { type: 'error' });
    }
  };

  const getStatusTone = (status: string) => {
    switch (status) {
      case 'APPROVED':
      case 'DEPOSITED':
        return 'success';
      case 'REJECTED':
      case 'CANCELLED':
        return 'error';
      case 'PENDING_APPROVAL':
      default:
        return 'warning';
    }
  };

  if (loading) return <LoadingView label="Loading collections…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Collections</Text>
          <Pressable 
            onPress={() => setShowExportModal(true)} 
            style={{ padding: 4, marginTop: -4 }}
          >
            <Download size={20} color={colors.ink2} />
          </Pressable>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search by ID, status, or mode…"
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>
      </View>

      <FlatList
        data={filteredCollections}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Pressable onPress={() => router.push({ pathname: '/(org)/collection/[id]', params: { id: item.id, item: JSON.stringify(item) } })}>
              <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, flex: 1 }}>
                    Collection #{item.id.slice(-6).toUpperCase()}
                  </Text>
                  <Badge tone={getStatusTone(item.status)} label={item.status.replace('_', ' ')} />
                </View>

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="caption" color="secondary">Mode: {item.paymentMode.replace('_', ' ')}</Text>
                  <Text variant="bodyMedium" style={{ fontWeight: '600', color: colors.accent }}>
                    {formatCurrency(item.amount)}
                  </Text>
                </View>

                <Divider style={{ marginVertical: 4 }} />

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="caption" color="secondary">{formatDate(item.collectionDate)}</Text>
                  <Text variant="caption" color="tertiary">Agent: {item.submittedBy.slice(-6).toUpperCase()}</Text>
                </View>
              </Card>
            </Pressable>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load collections" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={DollarSign}
                title="No collections recorded"
                message="Payments submitted by field agents will appear here."
              />
            )
          }
        />

      {/* Export Options Modal */}
      <Modal visible={showExportModal} animationType="fade" transparent>
        <Pressable style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'center', alignItems: 'center', padding: spacing.s4 }} onPress={() => setShowExportModal(false)}>
          <View style={{ backgroundColor: colors.canvas, borderRadius: radius.md, width: '100%', maxWidth: 300, overflow: 'hidden' }}>
            <View style={{ padding: spacing.s4, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Export Date Range</Text>
            </View>
            <Pressable onPress={() => { setShowExportModal(false); filterAndShareCSV('ALL'); }} style={{ padding: spacing.s4, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Text variant="bodyMedium">All time</Text>
            </Pressable>
            <Pressable onPress={() => { setShowExportModal(false); filterAndShareCSV('TODAY'); }} style={{ padding: spacing.s4, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Text variant="bodyMedium">Today</Text>
            </Pressable>
            <Pressable onPress={() => { setShowExportModal(false); filterAndShareCSV('MONTH'); }} style={{ padding: spacing.s4, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Text variant="bodyMedium">This month</Text>
            </Pressable>
            <Pressable onPress={() => { setShowExportModal(false); filterAndShareCSV('YEAR'); }} style={{ padding: spacing.s4 }}>
              <Text variant="bodyMedium">This year</Text>
            </Pressable>
          </View>
        </Pressable>
      </Modal>

    </Screen>
  );
}
