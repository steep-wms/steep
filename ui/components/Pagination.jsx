import classNames from "classnames"
import resolvedStyles from "./Pagination.scss?type=resolve"
import styles from "./Pagination.scss"
import { useRouter } from "next/router"
import Link from "next/link"

function makePages(curPage, numPages) {
  let pagemin = curPage - 2
  let pagemax = curPage + 3

  if (pagemin < 0) {
    pagemin = 0
    pagemax = 5
  }
  if (pagemax > numPages) {
    pagemax = numPages
    pagemin = Math.max(0, numPages - 5)
  }

  let pages = []
  for (let i = pagemin; i < pagemax; ++i) {
    pages.push(i + 1)
  }

  if (pages[0] > 2) {
    pages.unshift({ text: "…", id: "left-hellip" })
  }
  if (pages[pages.length - 1] < numPages - 1) {
    pages.push({ text: "…", id: "right-hellip" })
  }
  if (pages[0] !== 1) {
    pages.unshift(1)
  }
  if (pages[pages.length - 1] !== numPages) {
    pages.push(numPages)
  }

  return pages
}

function makeQueryWithOffset(query, page, pageSize) {
  query = { ...query }
  query.offset = page * pageSize
  if (query.offset <= 0) {
    delete query.offset
  }
  return query
}

const Pagination = ({
  pageSize = 10,
  pageOffset = 0,
  pageTotal = 0,
  onChangeOffset
}) => {
  const router = useRouter()
  let pathname = router.pathname

  if (pageSize <= 0) {
    return <></>
  }

  let curPage = Math.floor(pageOffset / pageSize)
  let numPages = Math.ceil(pageTotal / pageSize)
  if (numPages <= 1) {
    return <></>
  }

  let pages = makePages(curPage, numPages)
  pages = pages.map(p => {
    let active = p === curPage + 1
    let key = p.id || p
    let text = p.text || p
    if (Number.isInteger(text)) {
      let query = makeQueryWithOffset(router.query, text - 1, pageSize)
      return (
        <div className={classNames("pagination-page", { active })} key={key}>
          <Link
            href={{ pathname, query }}
            className={classNames(resolvedStyles.className, "page-link")}
            onClick={() => onChangeOffset(query.offset || 0)}
          >
            {text}
          </Link>
          {resolvedStyles.styles}
          <style jsx>{styles}</style>
        </div>
      )
    } else {
      return (
        <div className="pagination-page disabled" key={key}>
          {text}
          {resolvedStyles.styles}
          <style jsx>{styles}</style>
        </div>
      )
    }
  })

  if (curPage > 0) {
    let query = makeQueryWithOffset(router.query, curPage - 1, pageSize)
    pages.unshift(
      <div className="pagination-page" key="prev-page">
        <Link
          href={{ pathname, query }}
          className={classNames(resolvedStyles.className, "page-link")}
          onClick={() => onChangeOffset(query.offset || 0)}
        >
          &laquo;
        </Link>
        {resolvedStyles.styles}
        <style jsx>{styles}</style>
      </div>
    )
  } else {
    pages.unshift(
      <div className="pagination-page disabled" key="prev-page">
        &laquo;
        {resolvedStyles.styles}
        <style jsx>{styles}</style>
      </div>
    )
  }

  if (curPage < numPages - 1) {
    let query = makeQueryWithOffset(router.query, curPage + 1, pageSize)
    pages.push(
      <div className="pagination-page" key="next-page">
        <Link
          href={{ pathname, query }}
          className={classNames(resolvedStyles.className, "page-link")}
          onClick={() => onChangeOffset(query.offset || 0)}
        >
          &raquo;
        </Link>
        {resolvedStyles.styles}
        <style jsx>{styles}</style>
      </div>
    )
  } else {
    pages.push(
      <div className="pagination-page disabled" key="next-page">
        &raquo;
        {resolvedStyles.styles}
        <style jsx>{styles}</style>
      </div>
    )
  }

  return (
    <div className="pagination">
      {pages}
      {resolvedStyles.styles}
      <style jsx>{styles}</style>
    </div>
  )
}

export default Pagination
