/*
 * Copyright (C) 2019. Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sweers.catchup.service.github

import android.graphics.Color
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Query
import com.apollographql.apollo3.cache.http.HttpFetchPolicy.NetworkOnly
import com.apollographql.apollo3.cache.http.withHttpFetchPolicy
import com.apollographql.apollo3.exception.ApolloException
import com.squareup.anvil.annotations.ContributesMultibinding
import com.squareup.anvil.annotations.ContributesTo
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.multibindings.IntoMap
import dev.zacsweers.catchup.appconfig.AppConfig
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.sweers.catchup.gemoji.EmojiMarkdownConverter
import io.sweers.catchup.gemoji.replaceMarkdownEmojisIn
import io.sweers.catchup.libraries.retrofitconverters.DecodingConverter
import io.sweers.catchup.libraries.retrofitconverters.delegatingCallFactory
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.Mark
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceIndex
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaIndex
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.TextService
import io.sweers.catchup.service.github.GitHubApi.Language.All
import io.sweers.catchup.service.github.GitHubApi.Since.DAILY
import io.sweers.catchup.service.github.model.SearchQuery
import io.sweers.catchup.service.github.model.TrendingTimespan
import io.sweers.catchup.service.github.type.LanguageOrder
import io.sweers.catchup.service.github.type.LanguageOrderField
import io.sweers.catchup.service.github.type.OrderDirection
import io.sweers.catchup.util.e
import io.sweers.catchup.util.nullIfBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.rxSingle
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

private const val SERVICE_KEY = "github"

@ServiceKey(SERVICE_KEY)
@ContributesMultibinding(ServiceIndex::class, boundType = Service::class)
class GitHubService @Inject constructor(
  @InternalApi private val serviceMeta: ServiceMeta,
  private val apolloClient: Lazy<ApolloClient>,
  private val emojiMarkdownConverter: Lazy<EmojiMarkdownConverter>,
  private val gitHubApi: Lazy<GitHubApi>
) :
  TextService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Single<DataResult> {
    return fetchByScraping()
      .onErrorResumeNext { t: Throwable ->
        e(t) { "GitHub trending scraping failed." }
        fetchByQuery(request)
      }
  }

  private fun fetchByScraping(): Single<DataResult> {
    return gitHubApi
      .get()
      .getTrending(language = All, since = DAILY)
      .flattenAsObservable { it }
      .map {
        with(it) {
          CatchUpItem(
            id = "$author/$repoName".hashCode().toLong(),
            title = "$author / $repoName",
            description = description,
            timestamp = null,
            score = "★" to stars,
            tag = language,
            itemClickUrl = url,
            mark = starsToday?.toString()?.let {
              Mark(
                text = "+$it",
                icon = R.drawable.ic_star_black_24dp,
                iconTintColor = languageColor?.let(Color::parseColor)
              )
            }
          )
        }
      }
      .toList()
      .map { DataResult(it, null) }
  }

  private fun fetchByQuery(request: DataRequest): Single<DataResult> {
    val query = SearchQuery(
      createdSince = TrendingTimespan.WEEK.createdSince(),
      minStars = 50
    )
      .toString()

    val searchQuery = rxSingle(Dispatchers.IO) {
      apolloClient.get()
        .query(
          GitHubSearchQuery(
            queryString = query,
            firstCount = 50,
            order = LanguageOrder(
              direction = OrderDirection.DESC,
              field_ = LanguageOrderField.SIZE
            ),
            after = request.pageId.nullIfBlank()
          ).toApolloRequest()
            .withHttpFetchPolicy(NetworkOnly)
        )
    }

    return searchQuery
      .doOnSuccess {
        if (it.hasErrors()) {
          throw ApolloException(it.errors.toString())
        }
      }
      .map { it.data!! }
      .flatMap { (search) ->
        Observable.fromIterable(search.nodes?.mapNotNull { it?.asRepository }.orEmpty())
          .map {
            with(it) {
              val description = description
                ?.let { " — ${emojiMarkdownConverter.get().replaceMarkdownEmojisIn(it)}" }
                .orEmpty()

              CatchUpItem(
                id = id.hashCode().toLong(),
                title = "$name$description",
                score = "★" to stargazers.totalCount,
                timestamp = createdAt,
                author = owner.login,
                tag = languages?.nodes?.firstOrNull()?.name,
                source = licenseInfo?.name,
                itemClickUrl = url.toString()
              )
            }
          }
          .toList()
          .map {
            if (search.pageInfo.hasNextPage) {
              DataResult(it, search.pageInfo.endCursor)
            } else {
              DataResult(it, null)
            }
          }
      }
  }
}

@ContributesTo(ServiceMetaIndex::class)
@Module
abstract class GitHubMetaModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun @receiver:InternalApi ServiceMeta.githubServiceMeta(): ServiceMeta

  companion object {

    @InternalApi
    @Provides
    @Reusable
    internal fun provideGitHubServiceMeta(): ServiceMeta = ServiceMeta(
      SERVICE_KEY,
      R.string.github,
      R.color.githubAccent,
      R.drawable.logo_github,
      firstPageKey = "",
      enabled = BuildConfig.GITHUB_DEVELOPER_TOKEN.run { !isNullOrEmpty() && !equals("null") }
    )
  }
}

@ContributesTo(ServiceIndex::class)
@Module(includes = [GitHubMetaModule::class])
object GitHubModule {

  @Provides
  internal fun provideGitHubService(
    client: Lazy<OkHttpClient>,
    rxJavaCallAdapterFactory: RxJava3CallAdapterFactory,
    appConfig: AppConfig
  ): GitHubApi {
    return Retrofit.Builder().baseUrl(GitHubApi.ENDPOINT)
      .delegatingCallFactory(client)
      .addCallAdapterFactory(rxJavaCallAdapterFactory)
      .addConverterFactory(DecodingConverter.newFactory(GitHubTrendingParser::parse))
      .validateEagerly(appConfig.isDebug)
      .build()
      .create(GitHubApi::class.java)
  }
}

private fun <D : Query.Data> Query<D>.toApolloRequest(): ApolloRequest<D> {
  return ApolloRequest(this)
}
