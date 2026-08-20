import React, { useCallback, useState, useRef, useEffect } from 'react';
import { FlatList, View, StyleSheet, Modal, Pressable, ScrollView, SafeAreaView, TextInput, Animated, Dimensions } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { TrendingUp, WifiOff, X, AlertTriangle, Clock, Calendar, User, FileText, CheckCircle2, Search, SlidersHorizontal, Download, ChevronLeft } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, Card, Badge, EmptyState, LoadingView, Divider, Button } from '@/components/ui';
import { ptpsApi } from '@/api/ptpsApi';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate, formatDateTime } from '@/utils/date';
import type { PtpResponse } from '@/types/domain';

const SCREEN_WIDTH = Dimensions.get('window').width;

export default function PtpListScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [ptps, setPtps] = useState<PtpResponse[]>([]);
  const [loadError, setLoadError] = useState(false);
  
  const [selectedPtp, setSelectedPtp] = useState<PtpResponse | null>(null);
  const [detailVisible, setDetailVisible] = useState(false);
  const slideAnim = useRef(new Animated.Value(SCREEN_WIDTH)).current;

  useEffect(() => {
    if (selectedPtp) {
      setDetailVisible(true);
      Animated.spring(slideAnim, {
        toValue: 0,
        useNativeDriver: true,
        tension: 65,
        friction: 11,
      }).start();
    } else {
      Animated.timing(slideAnim, {
        toValue: SCREEN_WIDTH,
        duration: 250,
        useNativeDriver: true,
      }).start(() => setDetailVisible(false));
    }
  }, [selectedPtp]);

  const [showExportModal, setShowExportModal] = useState(false);
  const [showFilterModal, setShowFilterModal] = useState(false);
  
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('');

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await ptpsApi.list({ size: 100 });
      setPtps(response.content ?? []);
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

  const getPtpBadgeTone = (status: string) => {
    switch (status) {
      case 'FULFILLED': return 'success';
      case 'PARTIALLY_FULFILLED': return 'info';
      case 'BROKEN': return 'error';
      case 'CANCELLED': return 'neutral';
      case 'PENDING':
      default: return 'warning';
    }
  };

  const filterAndShareCSV = async (type: 'ALL' | 'TODAY' | 'MONTH' | 'YEAR') => {
    const d = new Date();
    const todayStr = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
    const monthStartStr = `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-01`;
    const yearStartStr = `${d.getFullYear()}-01-01`;

    let expFiltered = ptps;
    if (type === 'TODAY') expFiltered = ptps.filter(p => p.promisedDate >= todayStr);
    else if (type === 'MONTH') expFiltered = ptps.filter(p => p.promisedDate >= monthStartStr);
    else if (type === 'YEAR') expFiltered = ptps.filter(p => p.promisedDate >= yearStartStr);

    try {
      const header = 'ID,Amount,Status,Date,Agent\n';
      const rows = expFiltered
        .map((p) => `${p.id},${p.promisedAmount},${p.status},${p.promisedDate},${p.agentName}`)
        .join('\n');
      import('react-native').then(({ Share }) => {
        Share.share({
          message: header + rows,
          title: 'Export PTPs',
        });
      });
    } catch (err) {
      // ignore
    }
  };

  const filteredPtps = ptps.filter(p => {
    if (filterStatus && p.status !== filterStatus) return false;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      if (!p.borrowerName?.toLowerCase().includes(q) && !p.loanNumber?.toLowerCase().includes(q)) return false;
    }
    return true;
  });

  if (loading) return <LoadingView label="Loading Promises to Pay…" />;

  return (
    <Screen edges={['top']}>
      <View style={{ gap: spacing.s4, paddingBottom: spacing.s4 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, flex: 1 }}>
            <Pressable onPress={() => router.back()} hitSlop={8}>
              <ChevronLeft size={24} color={colors.ink1} />
            </Pressable>
            <View>
              <Text variant="title">Promises to Pay</Text>
              <Text variant="caption" color="secondary">{filteredPtps.length} active promises</Text>
            </View>
          </View>
          <View style={{ flexDirection: 'row', gap: spacing.s3, alignItems: 'center' }}>
            <Pressable 
              onPress={() => setShowFilterModal(true)} 
              style={{ padding: 4, position: 'relative' }}
            >
              <SlidersHorizontal size={20} color={filterStatus || searchQuery ? colors.accent : colors.ink2} />
              {(filterStatus !== '' || searchQuery !== '') && (
                <View style={{ position: 'absolute', top: 2, right: 2, width: 8, height: 8, borderRadius: 4, backgroundColor: colors.accent, borderWidth: 1, borderColor: colors.canvas }} />
              )}
            </Pressable>
            <Pressable 
              onPress={() => setShowExportModal(true)} 
              style={{ padding: 4 }}
            >
              <Download size={20} color={colors.ink2} />
            </Pressable>
          </View>
        </View>

        <FlatList
          data={filteredPtps}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Pressable onPress={() => setSelectedPtp(item)}>
              <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1, flex: 1 }}>
                    {item.borrowerName || 'Unknown Borrower'}
                  </Text>
                  <Badge tone={getPtpBadgeTone(item.status)} label={item.status.replace('_', ' ')} />
                </View>

                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Loan: {item.loanNumber}</Text>
                  <Text variant="bodyMedium" style={{ fontWeight: '600', color: colors.accent }}>
                    {formatCurrency(item.promisedAmount)}
                  </Text>
                </View>

                <Divider style={{ marginVertical: 4 }} />

                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Promised: {formatDate(item.promisedDate)}</Text>
                  <Text variant="caption" color="tertiary">Logged by: {item.agentName}</Text>
                </View>
              </Card>
            </Pressable>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          scrollEnabled={false}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load PTPs" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={TrendingUp}
                title="No PTPs found"
                message="Promises to Pay logged by the team will be listed here."
              />
            )
          }
        />
      </View>

      {/* PTP Details — full-screen slide from right */}
      <Modal visible={detailVisible} animationType="none" transparent statusBarTranslucent>
        <Animated.View style={{ flex: 1, backgroundColor: colors.canvas, transform: [{ translateX: slideAnim }] }}>
          <SafeAreaView style={{ flex: 1 }}>
            {/* Header */}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3, padding: spacing.s4, paddingTop: 48, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <Pressable onPress={() => setSelectedPtp(null)} hitSlop={8}>
                <ChevronLeft size={24} color={colors.ink1} />
              </Pressable>
              <View style={{ flex: 1 }}>
                <Text variant="headline">{selectedPtp?.borrowerName}</Text>
                <Text variant="caption" color="secondary">Loan #{selectedPtp?.loanNumber}</Text>
              </View>
              <Badge tone={getPtpBadgeTone(selectedPtp?.status || '')} label={(selectedPtp?.status || '').replace('_', ' ')} />
            </View>

            <ScrollView contentContainerStyle={{ padding: spacing.s4, gap: spacing.s5, paddingBottom: 40 }}>

              {/* Overdue / Reminder pills */}
              <View style={{ flexDirection: 'row', gap: spacing.s2, flexWrap: 'wrap' }}>
                {selectedPtp?.status === 'PENDING' && new Date(selectedPtp.promisedDate) < new Date() && (
                  <Badge tone="error" label="Overdue" />
                )}
                {selectedPtp?.reminderSent && (
                  <Badge tone="neutral" label="Reminder sent" />
                )}
              </View>

              {/* Stat Grid */}
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.s3 }}>
                {[
                  { label: 'Promised', value: formatCurrency(selectedPtp?.promisedAmount || 0), color: colors.ink1 },
                  { label: 'Collected', value: formatCurrency(selectedPtp?.collectedAmount || 0), color: (selectedPtp?.collectedAmount || 0) > 0 ? colors.accent : colors.ink1 },
                  { label: 'Promised Date', value: formatDate(selectedPtp?.promisedDate || ''), color: (selectedPtp?.status === 'PENDING' && new Date(selectedPtp!.promisedDate) < new Date()) ? colors.error : colors.ink1 },
                  { label: 'Fulfillment', value: `${selectedPtp?.fulfillmentPercentage?.toFixed(0) || '0'}%`, color: colors.ink1 },
                ].map(({ label, value, color }) => (
                  <View key={label} style={{ flexBasis: '47%', backgroundColor: colors.subtle, borderRadius: radius.md, padding: spacing.s3 }}>
                    <Text variant="caption" color="secondary">{label}</Text>
                    <Text variant="bodyMedium" style={{ fontWeight: '700', color, marginTop: 2 }}>{value}</Text>
                  </View>
                ))}
              </View>

              {/* Loan Info */}
              <View style={{ backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.s4, gap: spacing.s3 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <FileText size={14} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Loan Information</Text>
                </View>
                <Divider />
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Borrower</Text>
                  <Text variant="caption" style={{ fontWeight: '500' }}>{selectedPtp?.borrowerName}</Text>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Loan number</Text>
                  <Text variant="caption" style={{ fontWeight: '500' }}>{selectedPtp?.loanNumber}</Text>
                </View>
              </View>

              {/* Agent */}
              <View style={{ backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.s4, gap: spacing.s3 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <User size={14} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Agent</Text>
                </View>
                <Divider />
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Name</Text>
                  <Text variant="caption" style={{ fontWeight: '500' }}>{selectedPtp?.agentName}</Text>
                </View>
              </View>

              {/* Promise Details */}
              <View style={{ backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.s4, gap: spacing.s3 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <FileText size={14} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Promise Details</Text>
                </View>
                <Divider />
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Promised amount</Text>
                  <Text variant="caption" style={{ fontWeight: '600' }}>{formatCurrency(selectedPtp?.promisedAmount || 0)}</Text>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Promised date</Text>
                  <Text variant="caption" style={{ fontWeight: '500' }}>{formatDate(selectedPtp?.promisedDate || '')}</Text>
                </View>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Amount collected</Text>
                  <Text variant="caption" style={{ fontWeight: '600', color: (selectedPtp?.collectedAmount || 0) > 0 ? colors.accent : colors.ink1 }}>{formatCurrency(selectedPtp?.collectedAmount || 0)}</Text>
                </View>
                {selectedPtp?.contactNotes && (
                  <View style={{ gap: 4, marginTop: 4 }}>
                    <Text variant="caption" color="secondary">Contact notes</Text>
                    <Text variant="caption">{selectedPtp.contactNotes}</Text>
                  </View>
                )}
              </View>

              {/* Broken / Cancelled Reason */}
              {(selectedPtp?.brokenReason || selectedPtp?.cancellationReason) && (
                <View style={{ backgroundColor: '#FEF2F2', borderRadius: radius.md, padding: spacing.s4, gap: spacing.s3, borderWidth: 1, borderColor: '#FECACA' }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <AlertTriangle size={14} color={colors.error} />
                    <Text variant="bodyMedium" style={{ fontWeight: '600', color: colors.error }}>Reason</Text>
                  </View>
                  {selectedPtp?.brokenReason && (
                    <View style={{ gap: 4 }}>
                      <Text variant="caption" color="secondary">Broken reason</Text>
                      <Text variant="caption" style={{ color: colors.error }}>{selectedPtp.brokenReason}</Text>
                    </View>
                  )}
                  {selectedPtp?.cancellationReason && (
                    <View style={{ gap: 4 }}>
                      <Text variant="caption" color="secondary">Cancellation reason</Text>
                      <Text variant="caption">{selectedPtp.cancellationReason}</Text>
                    </View>
                  )}
                </View>
              )}

              {/* Timeline */}
              <View style={{ backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.s4, gap: spacing.s3 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <Calendar size={14} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Timeline</Text>
                </View>
                <Divider />
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Created</Text>
                  <Text variant="caption" style={{ fontWeight: '500' }}>{formatDateTime(selectedPtp?.createdAt || '')}</Text>
                </View>
                {selectedPtp?.fulfilledAt && (
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                    <Text variant="caption" color="secondary">Fulfilled at</Text>
                    <Text variant="caption" style={{ fontWeight: '500' }}>{formatDateTime(selectedPtp.fulfilledAt)}</Text>
                  </View>
                )}
                {selectedPtp?.brokenAt && (
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                    <Text variant="caption" color="secondary">Broken at</Text>
                    <Text variant="caption" style={{ fontWeight: '500' }}>{formatDateTime(selectedPtp.brokenAt)}</Text>
                  </View>
                )}
                {selectedPtp?.reminderSentAt && (
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                    <Text variant="caption" color="secondary">Reminder sent</Text>
                    <Text variant="caption" style={{ fontWeight: '500' }}>{formatDateTime(selectedPtp.reminderSentAt)}</Text>
                  </View>
                )}
              </View>

            </ScrollView>
          </SafeAreaView>
        </Animated.View>
      </Modal>

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

      {/* Filter Modal */}
      <Modal visible={showFilterModal} animationType="fade" transparent>
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'center', alignItems: 'center', padding: spacing.s4 }}>
          <View style={{ backgroundColor: colors.canvas, borderRadius: radius.md, width: '100%', maxWidth: 350, padding: spacing.s4, gap: spacing.s4 }}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
              <Text variant="bodyMedium" style={{ fontWeight: '600' }}>Filter Promises</Text>
              <Pressable onPress={() => setShowFilterModal(false)}><X size={20} color={colors.ink2} /></Pressable>
            </View>

            <View style={{ gap: 8 }}>
              <Text variant="caption" style={{ fontWeight: '500' }}>Search</Text>
              <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.subtle, borderRadius: radius.sm, paddingHorizontal: spacing.s2, borderWidth: 1, borderColor: colors.border }}>
                <Search size={14} color={colors.ink3} />
                <TextInput
                  value={searchQuery}
                  onChangeText={setSearchQuery}
                  placeholder="Borrower name or loan #"
                  placeholderTextColor={colors.ink3}
                  style={{ flex: 1, paddingVertical: spacing.s2, paddingHorizontal: spacing.s2, color: colors.ink1 }}
                />
                {searchQuery.length > 0 && (
                  <Pressable onPress={() => setSearchQuery('')}><X size={14} color={colors.ink3} /></Pressable>
                )}
              </View>
            </View>

            <View style={{ gap: 8 }}>
              <Text variant="caption" style={{ fontWeight: '500' }}>Status</Text>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                {['', 'PENDING', 'FULFILLED', 'PARTIALLY_FULFILLED', 'BROKEN', 'CANCELLED'].map(status => (
                  <Pressable
                    key={status}
                    onPress={() => setFilterStatus(status)}
                    style={{
                      paddingHorizontal: 12, paddingVertical: 6, borderRadius: radius.pill,
                      backgroundColor: filterStatus === status ? colors.accent : colors.subtle,
                      borderWidth: 1, borderColor: filterStatus === status ? colors.accent : colors.border
                    }}
                  >
                    <Text style={{ color: filterStatus === status ? colors.canvas : colors.ink1, fontSize: 12 }}>
                      {status === '' ? 'All' : status.replace('_', ' ')}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </View>

            <View style={{ flexDirection: 'row', gap: spacing.s2, marginTop: spacing.s2 }}>
              <Button label="Clear" variant="outline" onPress={() => { setSearchQuery(''); setFilterStatus(''); }} style={{ flex: 1 }} />
              <Button label="Apply" variant="primary" onPress={() => setShowFilterModal(false)} style={{ flex: 1 }} />
            </View>
          </View>
        </View>
      </Modal>

    </Screen>
  );
}
