import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable, Modal, ScrollView, SafeAreaView } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, Layers } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Button, Divider } from '@/components/ui';
import { CaseRow } from '@/components/CaseRow';
import { allocationsApi } from '@/api/allocationsApi';
import { resolveAmount, formatCurrency } from '@/utils/allocationHeuristics';
import type { AllocationResponse } from '@/types/domain';

function humanizeKey(key: string): string {
  return key.replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').replace(/\b\w/g, c => c.toUpperCase());
}
function formatDynamicValue(value: unknown): string {
  return (value == null || value === '') ? '—' : String(value);
}

export default function LoansScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loans, setLoans] = useState<AllocationResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);
  const [selectedLoan, setSelectedLoan] = useState<AllocationResponse | null>(null);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await allocationsApi.listAllocations({ size: 100 });
      setLoans(response.content ?? []);
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

  const filtered = loans.filter((c) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return c.borrowerName?.toLowerCase().includes(q) || c.loanNumber?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading loans…" />;

  return (
    <Screen edges={['top']}>
      <View style={{ gap: spacing.s4, paddingBottom: spacing.s4 }}>
        <View>
          <Text variant="title">Loans & Allocations</Text>
          <Text variant="caption" color="secondary">{loans.length} total loans registered</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border,
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search borrower or loan ID…"
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ gap: spacing.s2 }}
          renderItem={({ item }) => <CaseRow item={item} onPress={() => setSelectedLoan(item)} />}
          refreshing={refreshing}
          onRefresh={onRefresh}
          scrollEnabled={false}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load loans" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Layers}
                title="No loans found"
                message={search ? 'Try adjusting your search.' : 'No loans are currently registered.'}
              />
            )
          }
        />
      </View>

      {/* Loan Details Modal */}
      <Modal visible={!!selectedLoan} animationType="slide" transparent>
        <View style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' }}>
          <View style={{ backgroundColor: colors.canvas, borderTopLeftRadius: radius.lg, borderTopRightRadius: radius.lg, maxHeight: '90%', flex: 1 }}>
            
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', padding: spacing.s4, borderBottomWidth: 1, borderBottomColor: colors.border }}>
              <View style={{ flex: 1 }}>
                <Text variant="title" style={{ fontSize: 20 }}>{selectedLoan?.borrowerName || 'Loan Details'}</Text>
                <Text variant="caption" color="secondary" style={{ marginTop: 2 }}>{selectedLoan?.loanNumber}</Text>
              </View>
              <Pressable onPress={() => setSelectedLoan(null)} style={{ padding: 4 }}>
                <X size={20} color={colors.ink2} />
              </Pressable>
            </View>

            <ScrollView contentContainerStyle={{ padding: spacing.s4, gap: spacing.s4 }}>
              <Card style={{ gap: spacing.s3 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Outstanding</Text>
                  <Text variant="bodyMedium">{formatCurrency(selectedLoan ? resolveAmount(selectedLoan) : 0)}</Text>
                </View>
                <Divider />
                <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                  <Text variant="caption" color="secondary">Total due</Text>
                  <Text variant="bodyMedium">{formatCurrency(selectedLoan?.totalDue ?? null)}</Text>
                </View>
                {selectedLoan?.emi != null ? (
                  <>
                    <Divider />
                    <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                      <Text variant="caption" color="secondary">EMI</Text>
                      <Text variant="bodyMedium">{formatCurrency(selectedLoan.emi)}</Text>
                    </View>
                  </>
                ) : null}
              </Card>

              {selectedLoan?.dynamicData && Object.keys(selectedLoan.dynamicData).length > 0 ? (
                <Card style={{ gap: spacing.s2 }}>
                  <Text variant="headline">Additional details</Text>
                  {Object.entries(selectedLoan.dynamicData).map(([key, value]) => (
                    <View key={key} style={{ flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.s1 }}>
                      <Text variant="caption" color="secondary" style={{ flex: 1 }}>{humanizeKey(key)}</Text>
                      <Text variant="body" style={{ flex: 1, textAlign: 'right' }}>{formatDynamicValue(value)}</Text>
                    </View>
                  ))}
                </Card>
              ) : null}
            </ScrollView>
            
            <View style={{ padding: spacing.s4, borderTopWidth: 1, borderTopColor: colors.border }}>
              <Button label="Done" onPress={() => setSelectedLoan(null)} />
            </View>
            <SafeAreaView />
          </View>
        </View>
      </Modal>

    </Screen>
  );
}
const styles = StyleSheet.create({});
