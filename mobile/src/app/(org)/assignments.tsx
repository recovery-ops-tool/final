import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { View, FlatList, Pressable, ActivityIndicator, Alert, TextInput, StyleSheet } from 'react-native';
import { Screen, Text, Card, SegmentedTabs, Button, EmptyState, Badge } from '@/components/ui';
import { useTheme } from '@/theme/useTheme';
import { useToast } from '@/context/ToastContext';
import { usersApi, UserResponse } from '@/api/usersApi';
import { allocationsApi } from '@/api/allocationsApi';
import type { AllocationResponse } from '@/types/domain';
import { CheckSquare, Square, Search, Briefcase, ChevronDown, ChevronUp } from 'lucide-react-native';
import { formatCurrency } from '@/utils/allocationHeuristics';

export default function CaseAssignmentsScreen() {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();
  
  const [activeTab, setActiveTab] = useState('assign');
  const [fos, setFos] = useState<UserResponse[]>([]);
  const [selectedFo, setSelectedFo] = useState<string>('');
  const [loadingFos, setLoadingFos] = useState(true);
  
  const [cases, setCases] = useState<AllocationResponse[]>([]);
  const [loadingCases, setLoadingCases] = useState(false);
  
  const [search, setSearch] = useState('');
  const [selectedCases, setSelectedCases] = useState<Set<string>>(new Set());
  const [submitting, setSubmitting] = useState(false);
  const [showOfficerPanel, setShowOfficerPanel] = useState(false);

  const tabs = [
    { key: 'assign', title: 'Assign New' },
    { key: 'reassign', title: 'Reassign' },
  ];

  // Fetch FOs
  useEffect(() => {
    usersApi.listUsers(0, 100, 'createdAt', 'desc', 'FO')
      .then(res => {
        setFos(res.content);
        if (res.content.length > 0) setSelectedFo(res.content[0].id);
      })
      .catch(() => {})
      .finally(() => setLoadingFos(false));
  }, []);

  // Fetch Cases based on tab
  const loadCases = useCallback(async () => {
    setLoadingCases(true);
    setSelectedCases(new Set());
    try {
      const res = await allocationsApi.listAllocations({
        status: activeTab === 'assign' ? 'UNASSIGNED' : 'ASSIGNED',
        size: 200
      });
      setCases(res.content);
    } catch (e) {
      showToast('Failed to load cases', { type: 'error' });
    } finally {
      setLoadingCases(false);
    }
  }, [activeTab, showToast]);

  useEffect(() => {
    loadCases();
  }, [loadCases]);

  const toggleCase = (id: string) => {
    const next = new Set(selectedCases);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelectedCases(next);
  };

  const filteredCases = useMemo(() => {
    if (!search.trim()) return cases;
    const q = search.toLowerCase();
    return cases.filter(c => 
      c.borrowerName?.toLowerCase().includes(q) || 
      c.loanNumber?.toLowerCase().includes(q)
    );
  }, [cases, search]);

  const handleAssign = () => {
    if (!selectedFo) {
      showToast('Please select a Field Officer first', { type: 'warning' });
      return;
    }
    if (selectedCases.size === 0) {
      showToast('Please select at least one case', { type: 'warning' });
      return;
    }

    const foName = fos.find(f => f.id === selectedFo)?.firstName || 'Executive';
    
    Alert.alert(
      'Confirm Assignment',
      `Assign ${selectedCases.size} case(s) to ${foName}?`,
      [
        { text: 'Cancel', style: 'cancel' },
        { 
          text: 'Confirm', 
          onPress: async () => {
            setSubmitting(true);
            try {
              await allocationsApi.bulkAssign(Array.from(selectedCases), selectedFo);
              showToast('Cases assigned successfully', { type: 'success' });
              loadCases(); // reload list
            } catch {
              showToast('Failed to assign cases', { type: 'error' });
            } finally {
              setSubmitting(false);
            }
          }
        }
      ]
    );
  };

  const renderCase = ({ item }: { item: AllocationResponse }) => {
    const isSelected = selectedCases.has(item.id);
    return (
      <Pressable onPress={() => toggleCase(item.id)} style={{ marginBottom: spacing.s3 }}>
        <Card style={{ padding: spacing.s3, flexDirection: 'row', alignItems: 'center', gap: spacing.s3, borderWidth: isSelected ? 2 : 1, borderColor: isSelected ? colors.accent : colors.border }}>
          {isSelected ? <CheckSquare color={colors.accent} size={20} /> : <Square color={colors.ink3} size={20} />}
          <View style={{ flex: 1 }}>
            <Text variant="bodyMedium" style={{ fontWeight: '600' }}>{item.borrowerName || 'Unknown Borrower'}</Text>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
              <Text variant="caption" color="secondary">{item.loanNumber}</Text>
              <Text variant="caption" style={{ fontWeight: '600', color: colors.accent }}>{formatCurrency(item.outstandingAmount || 0)}</Text>
            </View>
            {activeTab === 'reassign' && item.agentName && (
              <View style={{ alignSelf: 'flex-start', marginTop: 6 }}>
                <Badge label={`Current: ${item.agentName}`} tone="neutral" />
              </View>
            )}
          </View>
        </Card>
      </Pressable>
    );
  };

  return (
    <Screen edges={['top']} padded={false}>
      <View style={{ padding: spacing.s4, paddingBottom: 0 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: spacing.s4 }}>
          <View style={{ flex: 1, paddingRight: spacing.s2 }}>
            <Text variant="title">Case Assignments</Text>
            <Text variant="caption" color="secondary">Allocate cases to field officer rosters</Text>
          </View>
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
        </View>
        
        {/* FO Selector */}
        {showOfficerPanel && (
          <View style={{ marginBottom: spacing.s4 }}>
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
                    onPress={() => setSelectedFo(item.id)}
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

        <SegmentedTabs
          tabs={tabs}
          activeTab={activeTab}
          onChange={setActiveTab}
        />
        
        <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3, marginVertical: spacing.s3, borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2 }}>
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search cases..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s2, paddingHorizontal: spacing.s2, color: colors.ink1 }}
          />
        </View>
      </View>

      <FlatList
        data={filteredCases}
        keyExtractor={c => c.id}
        contentContainerStyle={{ padding: spacing.s4, paddingBottom: spacing.s8 + 60 }}
        renderItem={renderCase}
        ListEmptyComponent={
          loadingCases ? <ActivityIndicator size="large" color={colors.accent} style={{ marginTop: spacing.s8 }} /> :
          <EmptyState icon={Briefcase} title="No cases found" message={activeTab === 'assign' ? "No unassigned cases available." : "No assigned cases available."} />
        }
      />

      {/* Floating Action Button for Assign */}
      {selectedCases.size > 0 && (
        <View style={{ position: 'absolute', bottom: spacing.s4, left: spacing.s4, right: spacing.s4 }}>
          <Button
            label={`Assign ${selectedCases.size} Case${selectedCases.size > 1 ? 's' : ''}`}
            onPress={handleAssign}
            loading={submitting}
            style={{ shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.15, shadowRadius: 8, elevation: 4 }}
          />
        </View>
      )}
    </Screen>
  );
}

