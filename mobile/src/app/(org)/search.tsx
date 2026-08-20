import { useCallback, useState, useEffect } from 'react';
import { View, TextInput, Pressable, FlatList, ActivityIndicator } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import { Search as SearchIcon, ArrowLeft, X, SearchX } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Text, EmptyState } from '@/components/ui';
import { CaseRow } from '@/components/CaseRow';
import { allocationsApi } from '@/api/allocationsApi';
import type { AllocationResponse } from '@/types/domain';

export default function SearchScreen() {
  const { user, role } = useAuth();
  const { colors, spacing, radius } = useTheme();
  
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<AllocationResponse[]>([]);
  
  const isFieldRole = role === 'FO' || role === 'CALLER' || role === 'TRACER';

  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    const timer = setTimeout(async () => {
      try {
        if (isFieldRole) {
          const res = await allocationsApi.getMyCases(user?.id ?? '', { searchTerm: query, size: 20 });
          setResults(res.content);
        } else {
          const res = await allocationsApi.listAllocations({ searchTerm: query, size: 20 });
          setResults(res.content);
        }
      } catch (err) {
        console.warn('Search failed:', err);
      } finally {
        setLoading(false);
      }
    }, 400); // 400ms debounce
    
    return () => clearTimeout(timer);
  }, [query, isFieldRole, user?.id]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: colors.canvas }} edges={['top']}>
      {/* Header with Search Bar */}
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: spacing.s4, paddingVertical: spacing.s3, gap: spacing.s3, borderBottomWidth: 1, borderBottomColor: colors.border, marginBottom: spacing.s2 }}>
        <Pressable onPress={() => router.back()} hitSlop={12}>
          <ArrowLeft size={24} color={colors.ink1} />
        </Pressable>
        
        <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3, height: 40 }}>
          <SearchIcon size={18} color={colors.ink3} style={{ marginRight: spacing.s2 }} />
          <TextInput
            autoFocus
            value={query}
            onChangeText={setQuery}
            placeholder="Search name, loan no, phone..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, color: colors.ink1, fontSize: 15, fontFamily: 'Inter_400Regular' }}
            returnKeyType="search"
          />
          {query.length > 0 && (
            <Pressable onPress={() => setQuery('')} hitSlop={8}>
              <X size={18} color={colors.ink3} />
            </Pressable>
          )}
        </View>
      </View>

      {/* Results */}
      {loading ? (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator size="large" color={colors.accent} />
          <Text variant="body" color="secondary" style={{ marginTop: spacing.s3 }}>Searching...</Text>
        </View>
      ) : (
        <FlatList
          data={results}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8 }}
          renderItem={({ item }) => <CaseRow item={item} />}
          keyboardShouldPersistTaps="handled"
          ListEmptyComponent={
            query.trim().length > 0 && !loading ? (
              <EmptyState icon={SearchX} title="No results found" message="Try adjusting your search terms." />
            ) : query.trim().length === 0 ? (
              <EmptyState icon={SearchIcon} title="Global Search" message="Search for borrowers, loan numbers, or phone numbers." />
            ) : null
          }
        />
      )}
    </SafeAreaView>
  );
}
