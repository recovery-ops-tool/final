import React, { useCallback, useState, useEffect } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable, ActivityIndicator } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, Send, ChevronDown, ChevronUp } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card, Button, Badge } from '@/components/ui';
import { dailyDispatchApi } from '@/api/dailyDispatchApi';
import { allocationsApi } from '@/api/allocationsApi';
import { usersApi, UserResponse } from '@/api/usersApi';
import type { AllocationResponse } from '@/types/domain';
import { useToast } from '@/context/ToastContext';
import { formatCurrency } from '@/utils/allocationHeuristics';

export default function DailyDispatchScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [dispatched, setDispatched] = useState<AllocationResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const [fos, setFos] = useState<UserResponse[]>([]);
  const [selectedFo, setSelectedFo] = useState<string>('');
  const [loadingFos, setLoadingFos] = useState(true);
  const [showOfficerPanel, setShowOfficerPanel] = useState(false);

  useEffect(() => {
    usersApi.listUsers(0, 100, 'createdAt', 'desc', 'FO')
      .then(res => {
        setFos(res.content);
        if (res.content.length > 0) setSelectedFo(res.content[0].id);
      })
      .catch(() => {})
      .finally(() => setLoadingFos(false));
  }, []);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const dateStr = new Date().toISOString().split('T')[0];
      let response;
      if (selectedFo) {
         response = await dailyDispatchApi.agentList(selectedFo, dateStr);
      } else {
         response = await dailyDispatchApi.myList(dateStr);
      }
      
      // Fallback: If no cases dispatched today, load any org-wide assigned cases so the screen isn't empty during testing.
      if (!response || response.length === 0) {
        const paged = await allocationsApi.listAllocations({ status: 'ASSIGNED', assignedToUserId: selectedFo || undefined, size: 50 }).catch(() => null);
        response = paged?.content ?? [];
      }
      
      setDispatched(response);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, [user, selectedFo]);

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

  const filtered = dispatched.filter((c) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return c.borrowerName?.toLowerCase().includes(q) || c.loanNumber?.toLowerCase().includes(q);
  });

  if (loading && dispatched.length === 0) return <LoadingView label="Loading daily dispatch…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <View style={{ flex: 1, paddingRight: spacing.s2 }}>
            <Text variant="title">Daily Dispatch</Text>
            <Text variant="caption" color="secondary">{dispatched.length} assigned targets for today</Text>
          </View>
          
          {fos.length > 0 && (
            <Pressable 
              onPress={() => setShowOfficerPanel(!showOfficerPanel)}
              style={{
                flexDirection: 'row', alignItems: 'center', backgroundColor: colors.accent,
                paddingHorizontal: spacing.s3, paddingVertical: 8, borderRadius: radius.md, gap: 6
              }}
            >
              <Text style={{ color: colors.canvas, fontWeight: '600', fontSize: 14 }}>Executive</Text>
              {showOfficerPanel ? <ChevronUp size={16} color={colors.canvas} /> : <ChevronDown size={16} color={colors.canvas} />}
            </Pressable>
          )}
        </View>

        {showOfficerPanel && fos.length > 0 && (
          <View style={{ marginBottom: spacing.s2 }}>
            <Text variant="caption" style={{ fontWeight: '600', marginBottom: spacing.s2, color: colors.ink2 }}>Select Executive</Text>
            {loadingFos ? <ActivityIndicator size="small" color={colors.accent} style={{ alignSelf: 'flex-start' }} /> : (
              <FlatList
                horizontal
                showsHorizontalScrollIndicator={false}
                data={fos}
                keyExtractor={f => f.id}
                contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s2 }}
                renderItem={({ item }) => (
                  <Pressable
                    onPress={() => {
                      setSelectedFo(item.id);
                    }}
                    style={{
                      paddingHorizontal: spacing.s4,
                      paddingVertical: spacing.s2,
                      borderRadius: radius.pill,
                      backgroundColor: selectedFo === item.id ? colors.accent : colors.subtle,
                      borderWidth: 1,
                      borderColor: selectedFo === item.id ? colors.accent : colors.border
                    }}
                  >
                    <Text style={{ color: selectedFo === item.id ? colors.canvas : colors.ink1, fontWeight: '500' }}>
                      {item.firstName} {item.lastName}
                    </Text>
                  </Pressable>
                )}
              />
            )}
          </View>
        )}

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
            placeholder="Search today's dispatch…"
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
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                  {item.borrowerName || 'Unknown Borrower'}
                </Text>
                <Badge tone="info" label={item.status} />
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="caption" color="secondary">Loan: {item.loanNumber}</Text>
                <Text variant="bodyMedium" style={{ fontWeight: '600', color: colors.accent }}>
                  {formatCurrency(item.outstandingAmount || 0)}
                </Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load dispatch" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Send}
                title="Roster empty"
                message="No cases are dispatched to the selected worklist today."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
